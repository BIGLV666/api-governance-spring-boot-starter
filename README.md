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
  后置链兜底（记录耗时 → 更新统计 → 日志记录 → 自定义），`pjp.proceed()` 只调用一次；内置过滤器可开关、可覆盖。
- ✅ **两种限流存储与两种算法**：本机限流（零依赖）/ Redis 限流（分布式，Lua 原子化），
  令牌桶（平滑、支持突发）/ 滑动窗口（精确）；注册 `RateLimiter` 或 `RateLimitStrategy` Bean 即可替换。
- ✅ **SpEL 参数维度限流**：`@RateLimit(key = "#userId")` 一个注解按参数值独立配额（受限求值上下文，安全）。
- ✅ **限流故障降级**：Redis 故障时可配 fail-open（放行）/ fail-close（503 拒绝），并触发告警。
- ✅ **标准限流响应头**：429 响应自动携带 `RateLimit-Limit` / `RateLimit-Remaining` / `RateLimit-Reset` / `Retry-After`。
- ✅ **动态限流规则**：管理接口运行期提交规则（支持尾部 `*` 通配），无需重启即可调整/放开/封禁；
  支持 TTL 自动失效、生效规则解释、变更审计、导入/导出与启动种子（规则即代码）。
- ✅ **Micrometer 与 Grafana**：治理指标自动暴露到 `/actuator/prometheus`，内置 10 面板仪表盘 JSON。
- ✅ **告警插件**：慢方法 / 限流拒绝 / 限流器故障事件回调自定义通知器，内置 Webhook 对接钉钉（含加签）/企微/飞书，
  支持告警风暴抑制、恢复通知与多实例集群去重。
- ✅ **bean / yml 双配置**：注册 Bean 覆盖、yml 配置默认，二选一。
- ✅ **内存指标 + 有界滑动窗口**：记录慢方法与响应情况，随进程关闭而销毁，内存永不膨胀。
- ✅ **后台管理接口**：供管理工具/运维平台查询与重置，可选静态令牌鉴权与只读模式。
- ✅ **异步方法钩子**：`@AsyncAction` + `@AsyncHandler` 支持任意公开 Spring Bean 方法的四阶段旁路任务，
  并提供执行指标、线程池水位、拒绝告警与启动期交叉校验。
- ✅ **治理范围可配置**：`include-packages` / `exclude-packages` 按包前缀批量圈定治理范围。
- ✅ **链路追踪可选**：不使用 OpenTelemetry 时零追踪栈开销，治理能力完全不受影响。
- ✅ **Actuator 健康与 Info**：`/actuator/health` 暴露限流器故障、规则存储陈旧与线程池饱和度，
  `/actuator/info` 展示实际生效配置。
- ✅ **轻量**：Redis / MQ / Actuator 为可选依赖，无 Lombok，告警 Webhook 基于 JDK HttpClient。
- ✅ **Boot 3.3–3.5 兼容**：基线 Spring Boot 3.5，支持 JDK 17/21。

---

## 快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>api-governance-spring-boot-starter</artifactId>
    <version>0.5.0</version>
</dependency>
```

> 上面使用 Maven Central 当前最新发布版本 **0.5.0**，可直接解析。仓库源码已到 **0.8.0**，
> 0.6.0–0.8.0 尚未发布；需要新版本能力时请从源码构建并安装到本地仓库。

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

核心配置入口为 `application.yml` 的 `api.governance.*`，完整配置项见
[配置参考](docs/configuration.md)。默认 `default-limit: -1`：管道会拦截统计，但不会拒绝请求。

---

## 文档导航

| 文档 | 内容 |
|------|------|
| [配置参考](docs/configuration.md) | `api.governance.*` 全部配置项与默认值 |
| [限流](docs/rate-limiting.md) | 本机 / Redis 限流、算法、SpEL 参数维度、标准响应头、故障降级、动态规则 TTL / 导入导出 / 启动种子 |
| [过滤器管道](docs/filters.md) | 自定义 `PreFilter` / `PostFilter` 插件与内置过滤器 |
| [异步方法插件](docs/async.md) | `@AsyncAction` / `@AsyncHandler` 与异步可观测性（[完整契约](docs/ASYNC_ACTIONS.md)） |
| [可观测性](docs/observability.md) | 内存指标、Micrometer 桥接、Grafana 面板、告警、Actuator 健康与 Info |
| [后台管理接口](docs/management-api.md) | 管理端点、规则解释 / 审计 / 导入导出、鉴权、写操作开关 |
| [分布式链路追踪](docs/tracing.md) | OpenTelemetry 集成与可选依赖说明 |
| [依赖说明](docs/dependencies.md) | 依赖矩阵、兼容范围与可选依赖策略 |
| [版本升级](docs/upgrade.md) | 各版本迁移注意事项 |
| [更新日志](CHANGELOG.md) | 按版本汇总的新增、变更与修复 |
| [故障演练](drills/README.md) | Redis 故障降级 / 线程池饱和 / 告警生命周期（可复现） |
| [可运行示例](examples/api-governance-example) | 最小示例工程（含演练 profile） |
| [English Guide](docs/guide-en.md) | 完整英文使用指南 |
| [English README](README_EN.md) | English overview |

## 许可证

本项目采用 [Apache License 2.0](./LICENSE) 开源。
