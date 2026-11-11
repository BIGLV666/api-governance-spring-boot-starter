# API Governance 示例工程

演示 [api-governance-spring-boot-starter](../../README.md) 1.0.0 核心特性的最小可运行工程。
本工程为**独立 Maven 工程**，不参与主仓库构建；请先在主仓库安装 starter，再运行示例。

## 运行

```bash
# 1. 主仓库根目录：安装 1.0.0 到本地仓库
./mvnw install -DskipTests

# 2. 运行示例（在仓库根目录执行；示例目录本身没有 Maven Wrapper）
./mvnw -f examples/api-governance-example/pom.xml spring-boot:run

# Windows
mvnw.cmd install -DskipTests
mvnw.cmd -f examples/api-governance-example/pom.xml spring-boot:run
```

启动后访问并观察控制台日志与管理接口：

| 演示点 | 操作 |
|--------|------|
| 自动日志 + 指标 | `curl http://localhost:8080/api/users/1` |
| SpEL 参数维度限流 | 快速访问 `http://localhost:8080/api/users/1`（每个 id 独立 3 次/10 秒配额，第 4 次被限流） |
| 全局限流 | 快速访问 `http://localhost:8080/api/orders`（5 次/10 秒） |
| 慢方法 + 告警 | `curl http://localhost:8080/api/slow`（延迟 1.5s，触发慢方法告警） |
| 并发上限（1.0.0） | 并发访问 `http://localhost:8080/api/bulkhead`（最多 2 个在途，第 3 个 429） |
| 自定义拒绝响应 | 被限流时返回自定义 JSON（见 `RejectHandlerConfig`） |
| 自定义告警通知器 | 控制台输出 `[ALERT] ...`（见 `ConsoleAlertNotifier`） |
| Micrometer 指标 | `curl http://localhost:8080/actuator/prometheus \| grep api.governance` |
| 内存指标 | `curl http://localhost:8080/api-governance/metrics` |
| 慢方法聚合 | `curl http://localhost:8080/api-governance/metrics/slow/all` |
| 管理接口鉴权 | `curl -H "X-Governance-Token: demo-token" http://localhost:8080/api-governance/status`（无令牌返回 401） |

## 关键文件

- `DemoApplication.java`：启动类
- `UserController.java`：`@RateLimit(key = "#id")` 参数维度限流
- `OrderController.java`：类级 `@RateLimit` + `@Skip` 健康检查 + `@ConcurrencyLimit` 并发演示
- `SlowController.java`：慢方法演示
- `RejectHandlerConfig.java`：`RateLimitRejectHandler` 自定义拒绝响应
- `ConsoleAlertNotifier.java`：`GovernanceAlertNotifier` 自定义告警
- `application.yml`：核心配置项示例（含管理接口令牌鉴权）
