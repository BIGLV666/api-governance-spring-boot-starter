# API Governance Spring Boot Starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.biglv666/api-governance-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.biglv666/api-governance-spring-boot-starter) [![CI](https://github.com/BIGLV666/api-governance-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/BIGLV666/api-governance-spring-boot-starter/actions/workflows/ci.yml)

> **核心理念：一切皆插件** —— 一个开箱即用、可自定义插拔的轻量级 API 治理 Starter。

[English](README_EN.md) | 中文

通过 **Controller 治理切面** 默认拦截所有 Controller 请求，以 **标准管道过滤器**（前置链 + 后置链）驱动
限流、日志、指标统计等能力。内置本地 / Redis 两种限流、令牌桶 / 滑动窗口两种算法，
支持自定义算法策略，提供后台管理接口，且**不引入过多外部依赖**。

---

## 特性

- ✅ **默认拦截所有 Controller 请求**：无需任何注解即可生效（治理 = 日志 + 指标统计）。
- ✅ **最少注解**：仅 `@RateLimit` / `@Skip` / `@NoLog` 三个注解，按需覆盖默认行为。
- ✅ **标准管道过滤器**：前置链短路（信息采集 → 流量统计 → 限流判断 → 自定义），
  后置链兜底（记录耗时 → 更新统计 → 日志记录 → 自定义），`pjp.proceed()` 只调用一次。
- ✅ **两种限流存储**：本机限流（零依赖）、Redis 限流（分布式，Redis 只封装 + Lua 原子化）。
- ✅ **两种过滤算法**：令牌桶（平滑限流、支持突发）、滑动窗口（精确限流）。
- ✅ **自定义算法策略**：注册 `RateLimiter` 或 `RateLimitStrategy` Bean 即可替换。
- ✅ **SpEL 参数维度限流**：`@RateLimit(key = "#userId")` 一个注解按参数值独立配额（受限求值上下文，安全）。
- ✅ **限流故障降级**：Redis 故障时可配 fail-open（放行）/ fail-close（503 拒绝），并触发告警。
- ✅ **Micrometer 指标桥接**：治理指标自动暴露到 `/actuator/prometheus` 等标准生态。
- ✅ **告警插件**：慢方法 / 限流拒绝 / 限流器故障事件回调自定义通知器，内置 Webhook 原生对接钉钉（含加签）/企微/飞书，带告警风暴抑制。
- ✅ **bean / yml 双配置**：注册 Bean 覆盖、yml 配置默认，二选一。
- ✅ **内存指标 + 有界滑动窗口**：记录慢方法与响应情况，随进程关闭而销毁，内存永不膨胀。
- ✅ **后台管理接口**：供管理工具/运维平台查询与重置，可选静态令牌鉴权。
- ✅ **轻量**：Redis / MQ 为可选依赖，无 Lombok，告警 Webhook 基于 JDK HttpClient。
- ✅ **异步方法钩子**：`@AsyncAction` + `@AsyncHandler` 支持任意公开 Spring Bean 方法的四阶段旁路任务。
- ✅ **内置过滤器可插拔**（0.3.0 新增）：5 个内置过滤器可按 `api.governance.filters.*` 开关，也可注册同类型 Bean 覆盖。
- ✅ **HTTP 请求上下文**（0.3.0 新增）：自定义过滤器可直接读取真实请求 URI、HTTP 方法与客户端 IP，无需自行解 `RequestContextHolder`。
- ✅ **治理范围可配置**（0.4.0 新增）：`include-packages` / `exclude-packages` 按包前缀批量圈定治理范围。
- ✅ **链路追踪为可选依赖**（0.4.0 新增）：不使用 OpenTelemetry 时零追踪栈开销，治理能力完全不受影响。
- ✅ **异步钩子可观测**（0.5.0 新增）：Handler 执行指标（次数/耗时/线程池水位）、管理端点注册清单、队列拒绝告警、启动期 action 交叉校验、内置 HTTP 上下文快照 enricher。
- ✅ **标准限流响应头**（0.6.0 新增）：429 响应自动携带 `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` / `Retry-After`（对齐 IETF ratelimit-headers 草案字段名），客户端可机器可读地退避。
- ✅ **告警恢复通知**（0.6.0 新增）：异常状况解除后（限流器故障后首次成功 / 慢方法安静超过抑制窗口）补发恢复事件，携带抑制期内被静默丢弃的告警条数。
- ✅ **集群限流回归防线**（0.6.0 新增）：3 master Redis 集群集成测试验证 `resetAll` 多节点扇出与 Lua 限流正确性（集群未启动时自动跳过，CI 已内置集群启动步骤）。
- ✅ **Grafana 面板**（0.6.0 新增）：内置 10 面板仪表盘 JSON（请求/拒绝/耗时分位/异步池水位），真实应用链路已验证，导入即用。

---

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>api-governance-spring-boot-starter</artifactId>
    <version>0.6.0</version>
</dependency>
```

### 2. 开箱即用

**什么都不用配**，Controller 的请求即会被自动拦截，输出访问日志并采集指标：

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")
    public User get(@PathVariable Long id) {
        return userService.findById(id);
    }
}
```

此时每个请求都会输出形如 `[API] GET /api/users/{id} - com.x.UserController#get - 成功 - 耗时: 12ms` 的日志，
并可在后台管理接口查询到该接口的调用次数、成功率、慢方法等指标。

### 3. 可选注解

```java
@RestController
@RequestMapping("/api/users")
@RateLimit(limit = 100)                    // 类级：默认 100 次/窗口
public class UserController {

    @GetMapping("/{id}")
    @NoLog                                 // 关闭该接口日志输出（统计仍保留）
    public User get(@PathVariable Long id) { ... }

    @PostMapping
    @RateLimit(limit = 5, window = 60)      // 方法级覆盖：60 秒内最多 5 次
    public User create(@RequestBody User u) { ... }

    @GetMapping("/health")
    @Skip                                   // 完全放行：不限流、不统计、不记日志
    public String health() { return "UP"; }
}
```

---

## 文档导航

| 文档 | 内容 |
|------|------|
| [配置参考](docs/configuration.md) | `api.governance.*` 全部配置项与默认值 |
| [限流](docs/rate-limiting.md) | 本机 / Redis 限流、算法、SpEL 参数维度、标准响应头、故障降级 |
| [过滤器管道](docs/filters.md) | 自定义 `PreFilter` / `PostFilter` 插件与内置过滤器 |
| [异步方法插件](docs/async.md) | `@AsyncAction` / `@AsyncHandler` 与异步可观测性（[完整契约](docs/ASYNC_ACTIONS.md)） |
| [可观测性](docs/observability.md) | 内存指标、Micrometer 桥接、Grafana 面板、告警与恢复通知 |
| [后台管理接口](docs/management-api.md) | 管理端点、鉴权、写操作开关 |
| [分布式链路追踪](docs/tracing.md) | OpenTelemetry 集成与可选依赖说明 |
| [依赖说明](docs/dependencies.md) | 依赖矩阵与可选依赖策略 |
| [版本升级](docs/upgrade.md) | 各版本迁移注意事项 |
| [故障演练](drills/README.md) | Redis 故障降级 / 线程池饱和 / 告警生命周期（可复现） |
| [可运行示例](examples/api-governance-example) | 最小示例工程（含演练 profile） |
| [English](README_EN.md) | English overview |

## 构建

```bash
mvnw.cmd clean install     # Windows
./mvnw clean install       # Linux/macOS
```

---

---

## 许可证

本项目采用 [Apache License 2.0](./LICENSE) 开源。
