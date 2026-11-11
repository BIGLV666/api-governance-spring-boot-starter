# 并发治理与过载保护（1.0.0 新增）

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 1. 为什么需要并发上限

限流控制的是「单位时间请求数」，**不控制同时在途的请求数**。一个接口限流
100 次/秒，当响应从 10ms 恶化到 2s 时，仍可能有 100 个请求同时占用线程；
再叠加两个慢接口，Tomcat 线程池会被打满，与它们无关的接口也会一起超时。

并发上限（bulkhead）限制「同一接口同时执行的数量」，超出的请求**立即拒绝**，
不排队、不占线程，把故障隔离在单个接口内。

## 2. 使用 `@ConcurrencyLimit`

```java
@RestController
public class OrderController {

    @PostMapping("/orders")
    @ConcurrencyLimit(permits = 10)   // 最多 10 个请求同时在途
    public Order create(@RequestBody Order order) { ... }
}
```

- 并发按 **API 标识**（`全限定类名#方法名`）分桶，不区分用户/参数——
  bulkhead 的目标是保护接口与线程池，不是做用户配额；
- 可标注在类或方法上，方法优先；
- `permits > 0`：并发上限；
- `permits = 0`：显式关闭（覆盖全局默认）；
- `permits = -1`（默认）：沿用全局 `default-permits`，未配置则不限制；
- 优先级：**动态规则 `maxConcurrent` > 方法注解 > 类注解 > 全局默认**。

名额在业务方法结束时于 `finally` 中归还，**异常、超时、过滤器短路路径都不会泄漏**。

> **Spring MVC 异步返回值限制**：`Callable` / `DeferredResult` / `WebAsyncTask` / `SseEmitter`
> 的 Controller 方法会在返回异步句柄时立即结束，permit 也随之释放，无法约束真正的异步执行。
> 需要并发保护的接口请使用同步返回值，或自行在异步完成回调中接入自定义
> `ConcurrencyLimiter`。
拒绝响应默认 429，携带 `Retry-After: 1`，响应体与普通治理拒绝一致：

```json
{"success": false, "code": "CONCURRENCY_LIMIT", "message": "并发请求过多，请稍后重试", "status": 429}
```

## 3. 动态调整（无需重启）

动态规则新增 `maxConcurrent` 字段，语义：

| 值 | 含义 |
|----|------|
| `> 0` | 覆盖注解/全局默认的并发上限 |
| `0` | 不干预（沿用注解/全局默认） |
| `-1` | 关闭该接口的并发限制 |

```bash
# 临时把订单创建接口的并发上限压到 3（limit=-1 表示不限流，只做并发控制）
curl -X PUT http://host:8080/api-governance/rate-limiter/rules \
  -H "Content-Type: application/json" \
  -d '{"pattern":"com.x.OrderController#create","limit":-1,"window":1,
       "maxConcurrent":3,"ttlSeconds":300,"reason":"下游抖动"}'
```

`GET /rate-limiter/rules/effective?apiKey=` 会返回 `concurrency` 节点，
给出注解值、全局默认、最终生效值与来源，便于排查。

## 4. 本地实现与参数

内置 `LocalConcurrencyLimiter`：按 API 标识分桶的 `Semaphore`，进程内生效。

```yaml
api:
  governance:
    concurrency-limit:
      enabled: true
      default-permits: -1       # 对所有未标注接口生效的默认上限
      max-entries: 10000        # 维护的 key 上限
      idle-timeout-ms: 600000   # 空闲桶淘汰阈值
      reject-status: 429
      message: "并发请求过多，请稍后重试"
```

- `tryAcquire` 非阻塞，达到上限立即返回拒绝；
- 按 `min(60s, idle-timeout-ms)` 的间隔扫描空闲桶；**只淘汰无在途请求的桶**，
  绝不回收正在使用的名额；
- key 数量超过上限且全部在用时允许临时增长并 warn —— 优先保证并发上限正确性；
- permits 热更新时创建新桶，已在途请求继续向旧桶归还，切换瞬间允许短暂重叠。

**集群语义**：1.0.0 只提供本地并发限制（单实例生效）。Redis 分布式信号量涉及
租约超时与实例宕机泄漏，暂不提供；需要全集群并发上限时请注册自定义
`ConcurrencyLimiter` Bean。

## 5. 请求体大小限制

默认关闭；开启后在反序列化前拒绝超大请求体：

```yaml
api:
  governance:
    body-size-limit:
      enabled: true
      max-bytes: 1048576    # 1 MiB
      reject-status: 413
      message: "请求体超过大小限制"
```

- `Content-Length` 已知且超限：直接 413，不读取 body；
- 分块传输（长度未知）：包装输入流按实际读取字节计数，超限中断并 413；
- **例外**：长度未知的 `application/x-www-form-urlencoded` / `multipart/*` 请求由 Servlet
  容器直接解析（`getParameter` / `getParts`），包装输入流无法约束，因此直接 413 拒绝，
  避免大小限制被静默绕过；这类请求请携带 `Content-Length`；
- 响应体为 `{"success":false,"code":"BODY_TOO_LARGE",...}`。

## 6. 可选 RFC 9457 错误体

默认关闭，保持既有 `{success, code, message, status, timestamp}` 结构。开启后
治理拒绝响应使用 `application/problem+json`：

```yaml
api:
  governance:
    problem-details:
      enabled: true
```

```json
{
  "type": "about:blank",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "请求过于频繁，请稍后重试",
  "instance": "/api/orders",
  "code": "REJECTED",
  "timestamp": "2026-11-11T00:00:00Z"
}
```

`Retry-After` / `RateLimit-*` 等治理响应头保持不变。

---
