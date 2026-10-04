# 限流

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

## 限流

### 1. 本机限流（默认，零依赖）

```yaml
api.governance.rate-limit.type: local
api.governance.rate-limit.algorithm: token-bucket   # 或 sliding-window
```

### 2. Redis 限流（分布式，可选依赖）

先引入 Redis 依赖，再配置：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>
```

```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
api:
  governance:
    rate-limit:
      type: redis
      algorithm: sliding-window   # 或 token-bucket
```

Redis 实现仅做「封装」：Lua 脚本原子化操作 Sorted Set / Hash，保证多实例一致。

### 3. 自定义算法策略（注册 Bean）

**方式 A：完全替换限流器**

```java
@Bean
public RateLimiter myRateLimiter() {
    return new RateLimiter() {
        @Override
        public boolean tryAcquire(String key, int limit, int windowSeconds) {
            // 自定义限流逻辑（自行维护 per-key 状态）
            return true;
        }
        @Override
        public String getName() { return "my-limiter"; }
    };
}
```

注册后自动优先于 yml 配置的默认限流器（`@ConditionalOnMissingBean`）。

**方式 B：仅自定义算法策略**

```java
@Bean
public RateLimitStrategy myStrategy() {
    // 函数式接口，也可用 Lambda 实现
    return (key, limit, window) -> {
        // 自定义算法
        return true;
    };
}
```

```yaml
api.governance.rate-limit.algorithm: custom
```

### 4. 限流颗粒度（限流键解析器）

默认按**接口（方法）维度**限流：限流键 = `全限定类名#方法名`，同一接口的所有请求共享配额。
类内存在同名映射方法（重载端点）时，键自动追加参数类型消歧（如 `com.x.UserController#get(Long)`），
避免两个重载端点互相消耗配额。
若想切换到**用户维度**、**IP 维度**、**接口+用户维度**等，实现 `RateLimitKeyResolver` 并注册 Bean 即可：

```java
@Bean
public RateLimitKeyResolver userRateLimitKeyResolver() {
    return context -> {
        // 从请求头 / 安全上下文 / ThreadLocal 获取当前用户
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String userId = attrs.getRequest().getHeader("X-User-Id");
        // 接口 + 用户维度：同一用户访问同一接口才共享配额
        return context.getApiKey() + "#user:" + userId;
    };
}
```

> 该 Bean 会自动覆盖默认实现（`@ConditionalOnMissingBean`），本机/Redis 限流均生效。
> 限流键变了，管理接口的重置参数也要用对应的新键（如 `com.x.UserController#get#user:42`）。

### 5. 参数维度限流（SpEL，0.2.0 新增）

最常见的「按用户/按参数限流」无需再手写解析器，直接在注解上声明 SpEL 表达式：

```java
@GetMapping("/users/{id}")
@RateLimit(limit = 10, key = "#id")       // 每个 id 独立 10 次/窗口
public User get(@PathVariable Long id) { ... }

@PostMapping("/login")
@RateLimit(limit = 5, window = 60, key = "#request.username")  // 按用户名独立配额
public Token login(@RequestBody LoginRequest request) { ... }
```

- 可用变量：方法参数（按参数名引用）与 `#apiKey`；
- 最终限流键 = `全限定类名#方法名:表达式结果`；
- 表达式在受限的 `SimpleEvaluationContext` 中求值：**不允许**类型引用、构造器调用与 Bean 引用；
- 表达式解析或求值失败时自动回退接口级限流（warn 日志），不影响业务；
- 需要结合请求头、安全上下文等复杂键时，仍建议实现 `RateLimitKeyResolver` Bean；
- **安全边界**：表达式结果由客户端可控输入派生时（如用户名、IP 等高基数参数），
  每个新取值都从满配额开始，「按参数限流」只能用于**租户间公平性**，不能作为防爆破等
  安全边界使用；此类场景应叠加接口级总配额或实现 `RateLimitKeyResolver` 组合 IP 等低基数维度。

### 6. 自定义限流拒绝响应（0.2.0 新增）

注册 `RateLimitRejectHandler` Bean 可完全自定义被限流后的响应行为：

```java
@Bean
public RateLimitRejectHandler rejectHandler() {
    return (context, rateLimitKey) -> {
        context.setRejectStatus(429);
        context.setRejectReason("每秒最多 " + context.getRateLimit() + " 次");
        context.setAttribute("retryAfterSeconds", context.getWindow());
    };
}
```

处理器抛出异常时自动回退默认拒绝行为（yml 配置的状态码与提示语），不影响短路语义。

### 7. Redis 故障降级策略（0.2.0 新增）

```yaml
api:
  governance:
    rate-limit:
      type: redis
      fail-strategy: open    # open=故障时放行（默认，可用性优先）/ close=故障时 503 拒绝（配额优先）
```

无论哪种策略，故障都会记录 error 日志并触发 `RATE_LIMITER_FAILURE` 告警（若已配置告警通知器）。
`fail-close` 的拒绝以 503 状态码返回，与普通 429 限流拒绝区分，便于运维定位。

### 8. 标准限流响应头（0.6.0 新增）

被限流的响应（默认 429，或自定义状态码）自动携带标准限流响应头，客户端可机器可读地退避：

| 响应头 | 值 | 说明 |
|--------|-----|------|
| `RateLimit-Limit` | 限流阈值 | 对齐 IETF ratelimit-headers 草案字段名 |
| `RateLimit-Remaining` | 0 | 拒绝时剩余配额为 0 |
| `RateLimit-Reset` | 窗口秒数 | 配额恢复的保守上界 |
| `Retry-After` | 窗口秒数 | 建议退避间隔 |

自定义 `RateLimitRejectHandler` 可通过 `context.addResponseHeader("Retry-After", "42")`
用同名头覆盖默认值；自定义 `PreFilter` 写入的头同样会随拒绝响应返回。
放行路径不携带这些头（避免 Redis 限流下每次请求多一次计数查询）。

### 9. 动态限流规则（0.7.0 新增）

运行期经管理接口提交的限流规则，**无需重启 / 重新发布**即可调整限流行为，
用于应急处置（事故时临时收紧、误杀时放开、临时封禁）：

```bash
# 给指定接口临时限流（100 次 / 60 秒）
curl -X PUT http://host:8080/api-governance/rate-limiter/rules \
  -H "Content-Type: application/json" \
  -H "X-Governance-Token: $GOVERNANCE_TOKEN" \
  -d '{"pattern": "com.x.OrderController#create", "limit": 100, "window": 60}'

# 前缀通配：整个控制器的所有端点
curl -X PUT ... -d '{"pattern": "com.x.OrderController#*", "limit": 50, "window": 1}'

# limit=-1 显式放开（覆盖接口上的 @RateLimit 注解）
curl -X PUT ... -d '{"pattern": "com.x.OrderController#create", "limit": -1}'

# limit=0 封禁（全部请求被 429 拒绝）
curl -X PUT ... -d '{"pattern": "com.x.OrderController#create", "limit": 0}'

# 查询 / 删除
curl http://host:8080/api-governance/rate-limiter/rules
curl -X DELETE "http://host:8080/api-governance/rate-limiter/rules?pattern=com.x.OrderController%23create"
```

**语义与优先级**：

- 优先级：**动态规则 &gt; 方法 `@RateLimit` &gt; 类 `@RateLimit` &gt; 全局默认**；
  规则只覆盖 `limit` / `window`，不影响 SpEL 参数维度限流键；
- `pattern` 支持精确匹配与**尾部 `*` 前缀通配**（普通 startsWith 语义，无单词边界：
  `com.x.User*` 会同时命中 `UserController` 与 `UserV2Controller`，需精确范围时请写到类名全称）；
- 多条规则命中同一 API 时：精确 &gt; 最长前缀；
- `limit`: -1 = 不限流（可覆盖注解）/ 0 = 封禁 / &gt;0 = 窗口内上限。

**存储与集群一致性**（随 `rate-limit.type` 联动）：

| type | 存储 | 写操作生效范围 |
|------|------|---------------|
| `local`（默认） | 内存（`in-memory`，volatile 快照读写无锁） | 仅当前实例 |
| `redis` | Redis Hash + 版本号（`redis`） | 全集群，传播延迟上界 = `refresh-interval-ms`（默认 5s） |

Redis 存储故障时 fail-stale：沿用最后一次成功快照继续生效（限频 warn），绝不影响业务请求；
恢复后自动追上最新版本。规则数上限 `max-rules`（默认 1000）防止误操作灌爆存储。

相关配置：

```yaml
api:
  governance:
    rate-limit:
      dynamic-rules:
        enabled: true               # 动态规则总开关（默认开，无规则时零行为变化）
        refresh-interval-ms: 5000   # Redis 模式版本轮询间隔（集群传播延迟上界）
        max-rules: 1000             # 规则数上限
```

> 自定义存储：注册 `RateRuleStore` Bean 可完全替换内置实现（接口契约：`findMatch`
> 位于请求热路径必须无锁、不做远程调用；`put` / `remove` 由管理接口低频调用）。

---
