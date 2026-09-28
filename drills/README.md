# 故障演练（Fault Drills）

在**真实应用 + 真实 Redis + 真实线程池**上验证治理组件的降级、告警与恢复行为。
全部场景可用下面的命令复现；演练使用示例工程自带的 `drill` profile
（[`examples/api-governance-example`](../examples/api-governance-example)），
不影响默认示例行为。

## 环境准备

```bash
# 1. 演练专用 Redis（6380，与其它应用隔离）
docker run -d --name gov-drill-redis -p 6380:6379 redis:7-alpine

# 2. 以 drill profile 启动示例（Redis 限流 + 5s 抑制窗口 + 1 线程 1 队列的极小异步池）
mvn -f examples/api-governance-example/pom.xml spring-boot:run \
    -Dspring-boot.run.profiles=drill

# 3. fail-close 变体（可选）：
mvn -f examples/api-governance-example/pom.xml spring-boot:run \
    -Dspring-boot.run.profiles=drill \
    -Dspring-boot.run.jvmArguments=-Dapi.governance.rate-limit.fail-strategy=close
```

示例工程的 `ConsoleAlertNotifier` 会把告警打印为
`[ALERT] 类型 ...`、恢复打印为 `[RECOVERED] 类型 ... 抑制期丢弃 N 条`，
以下证据均摘自该输出。

---

## 演练 1：Redis 故障降级（fail-open / fail-close / 恢复通知）

**步骤与实测**（应用以 `fail-strategy=open` 启动）：

| 步骤 | 命令 | 实测结果 |
|------|------|----------|
| 基线限流 | `for i in $(seq 1 6); do curl -s -o /dev/null -w "%{http_code} " localhost:8081/api/orders; done` | `200 200 200 200 200 429`，429 携带 `RateLimit-Limit/Remaining/Reset` + `Retry-After` 四个标准头 |
| Redis 宕机 | `docker stop gov-drill-redis` 后连续请求 | `200 200 200` —— **fail-open 放行，业务无感** |
| 故障告警 | 同上期间日志 | `[ALERT] RATE_LIMITER_FAILURE api=token-bucket-redis - 限流器故障: Unable to connect to Redis` |
| Redis 恢复 | `docker start gov-drill-redis` 后首个请求 | `200` + `[RECOVERED] RATE_LIMITER_FAILURE ... 抑制期丢弃 2 条` |

**fail-close 变体**（`fail-strategy=close`）：Redis 宕机时请求返回 **503**
（`{"code":"REJECTED","message":"限流服务暂不可用","status":503}`，与普通 429 明确区分），
恢复后首个成功请求同样触发 `[RECOVERED]`。

**演练发现**：Redis 故障时的降级速度取决于命令超时配置。示例 drill profile 显式设置
`spring.data.redis.timeout: 1s`；若未配置（Lettuce 默认 60s），Redis 断连会让每个请求线程
挂起至超时，fail-open 的「放行」实际要等 60 秒才发生——**治理组件必须配短超时**，
这条已写入 drill profile 注释。

---

## 演练 2：异步线程池饱和 → `ASYNC_TASK_REJECTED`

drill profile 将异步池配置为 `core=1, max=1, queue=1`，并发 6 次 `/api/heavy`
（旁路任务固定耗时 500ms）：

```bash
for i in $(seq 1 6); do curl -s -o /dev/null -w "%{http_code} " localhost:8081/api/heavy & done; wait
```

**实测**：6 个请求全部 `200`（**业务完全不受旁路任务影响**——异步插件的核心语义）；
旁路任务 1 个执行、1 个排队、**4 个被队列拒绝**：

```
[ALERT] ASYNC_TASK_REJECTED api=demo.heavy - 异步任务被拒绝: handler=...slowSideTask(AsyncEvent), 原因=...did not accept task
ERROR LoggingAsyncTaskRejectionHandler - Async task rejected: eventId=... action=demo.heavy phase=AFTER_SUCCESS
```

4 次拒绝在 5s 抑制窗口内合并为 1 条告警（其余 3 条静默并计数）——
**拒绝风暴不会打爆告警通道**，与恢复通知的计数语义衔接。

---

## 演练 3：慢方法「告警 → 抑制 → 恢复」完整生命周期

慢方法阈值 1s（`/api/slow` 固定 1.5s），抑制窗口 5s：

```bash
# 连续 3 次慢请求：1 条告警 + 2 条被抑制
for i in 1 2 3; do curl -s -o /dev/null localhost:8081/api/slow; done
# 静默 6 秒后（超过抑制窗口）再打 1 次：触发恢复扫描
sleep 6 && curl -s -o /dev/null localhost:8081/api/slow
```

**实测**：

```
[ALERT]     SLOW_METHOD ... 耗时 1505ms 超过阈值 1000ms        ← 首报
[RECOVERED] SLOW_METHOD ... 抑制期丢弃 2 条 - 告警恢复: SLOW_METHOD 已解除   ← 静默超过窗口，惰性扫描补发
[ALERT]     SLOW_METHOD ... 耗时 1508ms 超过阈值 1000ms        ← 状况复现，新一轮告警
```

一趟验证了 0.5.1 的固定窗口抑制与 0.6.0 的恢复通知在同一生命周期内协同工作。

---

## 结论

- 降级路径（fail-open 放行 / fail-close 503）与恢复通知在真实故障下行为符合设计，
  业务请求始终可用（fail-open）或明确拒绝（fail-close），无中间态；
- 告警抑制 + 恢复 + 计数在故障风暴下语义完整；
- 唯一需要**部署侧配合**的点：Redis（及任何治理依赖）必须配置短超时，
  否则降级被超时拖住——已作为演练发现记录并固化到 drill profile。