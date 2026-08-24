# API Governance Spring Boot Starter

> **核心理念：一切皆插件** —— 一个开箱即用、可自定义插拔的轻量级 API 治理 Starter。

通过 **Controller 治理切面** 默认拦截所有 Controller 请求，以 **标准管道过滤器**（前置链 + 后置链）驱动
限流、日志、指标统计等能力。内置本地 / Redis 两种限流、令牌桶 / 滑动窗口两种算法，
支持自定义算法策略，提供后台管理接口，且**不引入过多外部依赖**。

---

## 一、特性

- ✅ **默认拦截所有 Controller 请求**：无需任何注解即可生效（治理 = 日志 + 指标统计）。
- ✅ **最少注解**：仅 `@RateLimit` / `@Skip` / `@NoLog` 三个注解，按需覆盖默认行为。
- ✅ **标准管道过滤器**：前置链短路（信息采集 → 流量统计 → 限流判断 → 自定义），
  后置链兜底（记录耗时 → 更新统计 → 日志记录 → 自定义），`pjp.proceed()` 只调用一次。
- ✅ **两种限流存储**：本机限流（零依赖）、Redis 限流（分布式，Redis 只封装 + Lua 原子化）。
- ✅ **两种过滤算法**：令牌桶（平滑限流、支持突发）、滑动窗口（精确限流）。
- ✅ **自定义算法策略**：注册 `RateLimiter` 或 `RateLimitStrategy` Bean 即可替换。
- ✅ **bean / yml 双配置**：注册 Bean 覆盖、yml 配置默认，二选一。
- ✅ **内存指标 + 有界滑动窗口**：记录慢方法与响应情况，随进程关闭而销毁，内存永不膨胀。
- ✅ **后台管理接口**：供管理工具/运维平台查询与重置。
- ✅ **轻量**：仅依赖 `spring-boot-starter-aop` + `spring-web`，Redis 为可选依赖，无 Lombok。
- ✅ **异步方法钩子**：`@AsyncAction` + `@AsyncHandler` 支持任意公开 Spring Bean 方法的四阶段旁路任务。

---

## 二、快速开始

### 1. 引入依赖

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>api-governance-spring-boot-starter</artifactId>
    <version>0.1.0</version>
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

## 三、配置（application.yml）

```yaml
api:
  governance:
    enabled: true                     # 治理总开关（默认 true）
    log:
      enabled: true                   # 日志总开关（默认 true）
      log-request-params: false       # 是否输出入参（默认 false，避免敏感信息）
      log-response: false             # 是否输出响应体（默认 false）
      slow-threshold-ms: 1000         # 慢方法阈值（毫秒，默认 1000）
    rate-limit:
      type: local                     # local=本机 / redis=分布式
      algorithm: token-bucket         # token-bucket / sliding-window / custom
      default-limit: -1               # 全局默认限流阈值（-1=不限制）
      default-window: 1               # 全局默认窗口（秒）
      status-code: 429                # 限流拒绝的 HTTP 状态码
      message: "请求过于频繁，请稍后重试"  # 限流拒绝提示语
    metrics:
      window-size: 100                # 每个 API 保留的最近记录条数
      window-seconds: 300             # 记录保留时长（秒）
      max-apis: 1000                  # 最大统计 API 数量（超限 LRU 淘汰）
    management:
      enabled: true                   # 管理接口开关（默认 true）
      base-path: /api-governance      # 管理接口基础路径
    async:
      enabled: true                   # 异步方法生命周期插件开关
      core-pool-size: 2               # 独立线程池核心线程数
      max-pool-size: 8                # 独立线程池最大线程数
      queue-capacity: 1000            # 有界队列容量
      keep-alive-seconds: 60
      thread-name-prefix: api-governance-async-
      await-termination-seconds: 5
```

> **说明**：默认 `default-limit: -1`（不限流），即「拦截但不限流」。若希望全局限流，
> 将 `default-limit` 设为正值即可；个别接口可用 `@RateLimit` 覆盖。

---

## 四、限流

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

---

## 五、过滤器管道（自定义插件）

实现 `PreFilter` 或 `PostFilter` 并注册为 Spring Bean，即自动加入管道（按 `order` 排序）：

```java
@Component
@Order(300)   // 数字越小越先执行
public class ParamCheckFilter implements PreFilter {
    @Override
    public boolean doFilter(FilterContext ctx) {
        if (ctx.getArgs() == null || ctx.getArgs().length == 0) {
            ctx.setRejectStatus(400);
            ctx.setRejectReason("参数缺失");
            return false;   // 返回 false 短路，业务方法不会执行
        }
        return true;
    }
}
```

内置过滤器顺序：

| 阶段 | Order | 过滤器 | 职责 |
|------|-------|--------|------|
| 前置 | 1 | MetadataCollectorFilter | 信息采集（HTTP 方法/路径） |
| 前置 | 100 | TrafficStatisticsFilter | 流量统计（总请求数） |
| 前置 | 200 | RateLimitFilter | 限流判断 |
| 后置 | 400 | SlowMethodFilter | 记录耗时 + 更新统计 + 慢方法告警 |
| 后置 | 500 | LoggingFilter | 日志记录（响应情况） |

---

## 六、异步方法生命周期插件

目标方法保持同步执行，框架只在其生命周期阶段提交附加异步任务：

```java
@Service
public class LoginService {

    @AsyncAction("user.login")
    public LoginResult login(LoginRequest request) {
        return doLogin(request);
    }
}

@Component
public class LoginHandlers {

    @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_SUCCESS, order = 100)
    public void saveLoginLog(AsyncEvent event) {
        // 写 DB、发送通知或更新非关键统计
    }
}
```

支持 `BEFORE`、`AFTER_SUCCESS`、`AFTER_ERROR`、`AFTER_COMPLETION`。所有 Handler 默认异步且不改变原业务结果；`order` 只保证提交顺序，不保证完成顺序。跨线程只传递不可变事件快照，默认不捕获完整参数、返回值和原始异常。

完整使用方式、线程池替换、事件增强、安全边界与限制见 [ASYNC_ACTIONS.md](ASYNC_ACTIONS.md)。

---

## 七、内存指标统计

指标保存在内存中（随进程启动而创建、随进程关闭而销毁，不持久化）。为防止内存膨胀：

- 每个 API 的「最近请求记录」使用**有界滑动窗口**（`window-size` 条数上限 + `window-seconds` 时间上限）；
- 全局 API 数量上限 `max-apis`，超限按 LRU 淘汰。

记录内容：总请求数、成功/失败/拒绝数、慢方法数、最小/最大/平均耗时，以及最近请求明细。

---

## 八、后台管理接口

基础路径默认 `/api-governance`（可配置），供管理工具调用：

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/status` | 治理系统状态 |
| GET | `/config` | 当前配置 |
| GET | `/filters` | 过滤器链信息 |
| GET | `/rate-limiter/status` | 限流器状态 |
| GET | `/rate-limiter/count?key=` | 指定 key 当前计数 |
| POST | `/rate-limiter/reset?key=` | 重置指定 key |
| POST | `/rate-limiter/reset-all` | 重置全部限流 |
| GET | `/metrics` | 全部 API 指标汇总 |
| GET | `/metrics/detail?key=` | 单 API 明细（含最近记录） |
| GET | `/metrics/slow?key=` | 单 API 慢方法列表 |
| GET | `/metrics/slow/all` | **所有 API 慢方法聚合（Map）** |
| DELETE | `/metrics` | 清空全部指标 |
| DELETE | `/metrics/single?key=` | 清空指定 API 指标 |

> `key` 即 API 唯一标识，格式为 `全限定类名#方法名`，例如 `com.example.UserController#get`。
> 生产环境请通过网关鉴权 / IP 白名单等方式保护管理接口。

**慢方法聚合接口示例**：

```bash
# 获取所有 API 的慢方法记录（一次性查看全部，无需逐个查询）
GET /api-governance/metrics/slow/all
```

返回格式：

```json
{
  "slowThresholdMs": 1000,
  "totalApis": 2,
  "slowRecords": {
    "com.example.UserController#getUser": [
      {
        "timestamp": 1704038400000,
        "elapsedMs": 1200,
        "success": true,
        "slow": true,
        "httpMethod": "GET",
        "path": "/api/user",
        "error": null
      }
    ],
    "com.example.OrderController#create": [
      {
        "timestamp": 1704038410000,
        "elapsedMs": 2500,
        "success": false,
        "slow": true,
        "httpMethod": "POST",
        "path": "/api/order",
        "error": "timeout"
      },
      {
        "timestamp": 1704038405000,
        "elapsedMs": 1500,
        "success": true,
        "slow": true,
        "httpMethod": "POST",
        "path": "/api/order",
        "error": null
      }
    ]
  }
}
```

> 每个方法的慢请求列表按**时间倒序**排列（最新的在前），包含时间戳、耗时、请求方式、路径、错误信息等完整数据。

---

## 九、分布式链路追踪

Starter 内置 Micrometer Tracing、OpenTelemetry bridge 与 OTLP exporter。HTTP 请求、框架异步任务以及宿主已有的 Spring Kafka / Spring AMQP 组件会自动传播 W3C `traceparent`，业务代码不需要手动维护 `traceId`。

Kafka 和 RabbitMQ 依赖仍是可选的：宿主使用哪个中间件，就只激活哪个适配器。适配器只增强已有的 `KafkaTemplate`、`RabbitTemplate` 和监听容器，不创建或替换连接、序列化及监听配置。

本地开发不接 Collector 时无需配置。需要上报链路时只配置 OTLP 地址：

```yaml
spring:
  application:
    name: order-service

management:
  otlp:
    tracing:
      endpoint: http://otel-collector:4318/v1/traces
  tracing:
    sampling:
      probability: 0.1
```

默认能力可按需关闭：

```yaml
api:
  governance:
    tracing:
      enabled: true
      async-context-propagation: true
      kafka: true
      rabbit: true
```

日志格式可使用 Spring Boot 的关联字段：

```yaml
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

MQ 的 `traceparent`、`tracestate`、`baggage` 由框架写入消息 Header；业务仍应独立维护 `messageId` 和 `correlationId`，不要使用 `traceId` 做消费幂等。

---

## 十、依赖说明

| 依赖 | 作用 | 是否可选 |
|------|------|----------|
| spring-boot-starter-aop | AOP + 核心容器 | 否 |
| spring-web | @RestController 等 Web 注解 | 否 |
| spring-boot-starter-actuator | Observation 与追踪自动装配 | 否 |
| micrometer-tracing-bridge-otel | OpenTelemetry bridge | 否 |
| opentelemetry-exporter-otlp | OTLP 链路上报 | 否 |
| spring-kafka | Kafka 消息链路适配（宿主使用 Kafka 时激活） | 是 |
| spring-rabbit | RabbitMQ 消息链路适配（宿主使用 RabbitMQ 时激活） | 是 |
| spring-boot-configuration-processor | yml 配置元数据 | 是 |
| spring-boot-starter-data-redis | Redis 限流 | 是 |

> 未引入 `spring-boot-starter-web`（不捆绑内嵌容器）、未引入 Lombok，
> Redis 为可选依赖，仅在使用分布式限流时引入。

---

## 十一、文档

- [ARCHITECTURE.md](./ARCHITECTURE.md)：架构设计与维护迭代指南。
- [ASYNC_ACTIONS.md](./ASYNC_ACTIONS.md)：异步方法生命周期插件完整契约。
- [ASYNC_IMPLEMENTATION.md](./ASYNC_IMPLEMENTATION.md)：双切面、注册缓存、事件快照和调度实现原理。
- 源码中各方法均附详细中文注释。

## 十二、构建

```bash
mvnw.cmd clean install     # Windows
./mvnw clean install       # Linux/macOS
```

---

---

## 十三、许可证

本项目采用 [Apache License 2.0](./LICENSE) 开源。
