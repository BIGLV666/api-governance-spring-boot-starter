# API Governance Spring Boot Starter

> **Core philosophy: everything is a plugin** — a lightweight, batteries-included API governance starter with pluggable everything.

中文 | [English](README_EN.md)

Governance defaults to intercepting every Controller request through a single AOP aspect driven by a
**standard pipeline of filters** (pre-chain + post-chain): rate limiting, logging, metrics and more.
Built-in local / Redis rate limiting with token-bucket / sliding-window algorithms, custom algorithm
strategies, admin endpoints — with **no heavy external dependencies**.

---

## 1. Features

- ✅ **Intercepts all Controller requests by default** — no annotations required (governance = logging + metrics).
- ✅ **Minimal annotations** — only `@RateLimit` / `@Skip` / `@NoLog`, to override defaults where needed.
- ✅ **Standard filter pipeline** — pre-chain short-circuits (metadata → traffic stats → rate limit → custom),
  post-chain always runs (elapsed time → stats → logging → custom); `pjp.proceed()` is invoked exactly once.
- ✅ **Two rate-limit storages** — local (zero dependency) and Redis (distributed; thin wrapper + atomic Lua).
- ✅ **Two algorithms** — token bucket (smooth, allows bursts) and sliding window (precise).
- ✅ **Custom strategies** — register a `RateLimiter` or `RateLimitStrategy` bean to replace defaults.
- ✅ **SpEL per-parameter limiting** — `@RateLimit(key = "#userId")` gives each parameter value its own
  quota, evaluated in a restricted, security-hardened context.
- ✅ **Rate-limiter failure policy** — configurable fail-open (allow) / fail-close (503) when Redis is down,
  with alerting.
- ✅ **Micrometer bridge** — governance metrics exposed via `/actuator/prometheus` and friends.
- ✅ **Alerting plugin** — slow-method / rate-limit-reject / limiter-failure events delivered to custom
  notifiers; built-in webhook notifier (DingTalk/WeCom/Feishu) with alert-storm suppression.
- ✅ **Bean or YAML configuration** — register beans to override, or just configure YAML defaults.
- ✅ **In-memory metrics with bounded sliding windows** — memory never grows unbounded.
- ✅ **Admin endpoints** — query & reset for ops tooling, optional static-token authentication.
- ✅ **Lightweight** — Redis / MQ are optional dependencies; no Lombok; webhook alerting uses the JDK HttpClient.
- ✅ **Async method hooks** — `@AsyncAction` + `@AsyncHandler` run four-phase side tasks around any public
  Spring bean method.

---

## 2. Quick Start

### 1) Add the dependency

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>api-governance-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

### 2) It just works

**Configure nothing**; Controller requests are intercepted automatically with access logs and metrics:

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

Each request now logs `[API] GET /api/users/{id} - com.x.UserController#get - OK - took 12ms`,
and call counts, success rates and slow methods are queryable via the admin endpoints.

### 3) Optional annotations

```java
@RestController
@RequestMapping("/api/users")
@RateLimit(limit = 100)                    // class level: 100 requests per window by default
public class UserController {

    @GetMapping("/{id}")
    @NoLog                                 // disable logging for this endpoint (stats are kept)
    public User get(@PathVariable Long id) { ... }

    @PostMapping
    @RateLimit(limit = 5, window = 60)      // method override: 5 requests / 60 seconds
    public User create(@RequestBody User u) { ... }

    @GetMapping("/health")
    @Skip                                   // bypass governance entirely
    public String health() { return "UP"; }
}
```

---

## 3. Configuration (application.yml)

```yaml
api:
  governance:
    enabled: true                     # master switch (default true)
    log:
      enabled: true
      log-request-params: false       # off by default (sensitive data / log size)
      log-response: false
      slow-threshold-ms: 1000         # slow-method threshold
    rate-limit:
      type: local                     # local / redis
      algorithm: token-bucket         # token-bucket / sliding-window / custom
      default-limit: -1               # -1 = not limited
      default-window: 1               # seconds
      status-code: 429
      message: "Too many requests, please retry later"
      fail-strategy: open             # limiter failure: open=allow / close=503 (applies to Redis)
    metrics:
      window-size: 100
      window-seconds: 300
      max-apis: 1000                  # LRU eviction beyond this
      micrometer-enabled: true        # bridge to Micrometer when a MeterRegistry exists
    alert:
      enabled: true
      suppress-interval-ms: 10000     # min interval per (type, apiKey) to prevent alert storms
      webhook:
        enabled: false
        url: ""                       # webhook endpoint (DingTalk/WeCom/Feishu bot)
        timeout-ms: 3000
        secret-token: ""              # optional, sent as X-Governance-Token header
    management:
      enabled: true
      base-path: /api-governance
      auth-token: ""                  # non-empty enables token auth for admin endpoints
      auth-header: X-Governance-Token
    async:
      enabled: true
      core-pool-size: 2
      max-pool-size: 8
      queue-capacity: 1000
      keep-alive-seconds: 60
      thread-name-prefix: api-governance-async-
      await-termination-seconds: 5
```

> By default `default-limit: -1` (no limiting): the pipeline intercepts and measures but never rejects.
> Set it to a positive value for global limiting; individual endpoints can override via `@RateLimit`.

---

## 4. Rate Limiting

### 1) Local (default, zero dependency)

```yaml
api.governance.rate-limit.type: local
api.governance.rate-limit.algorithm: token-bucket   # or sliding-window
```

### 2) Redis (distributed, optional dependency)

Add the Redis starter, then:

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
      algorithm: sliding-window   # or token-bucket
```

Redis implementations are thin wrappers: Lua scripts atomically operate on Sorted Sets / Hashes so all
instances agree on the quota.

### 3) Custom algorithm strategy (bean)

**Option A: replace the limiter entirely**

```java
@Bean
public RateLimiter myRateLimiter() {
    return new RateLimiter() {
        @Override
        public boolean tryAcquire(String key, int limit, int windowSeconds) {
            return true;
        }
        @Override
        public String getName() { return "my-limiter"; }
    };
}
```

**Option B: only replace the algorithm**

```java
@Bean
public RateLimitStrategy myStrategy() {
    return (key, limit, window) -> true;
}
```

```yaml
api.governance.rate-limit.algorithm: custom
```

### 4) Limit granularity (key resolver)

By default the limit is **per endpoint**: key = `fully.qualified.ClassName#method`. Implement
`RateLimitKeyResolver` and register the bean to switch to user / IP / endpoint+user granularity:

```java
@Bean
public RateLimitKeyResolver userRateLimitKeyResolver() {
    return context -> {
        ServletRequestAttributes attrs =
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String userId = attrs.getRequest().getHeader("X-User-Id");
        return context.getApiKey() + "#user:" + userId;
    };
}
```

### 5) Per-parameter limiting (SpEL, new in 0.2.0)

For the common "limit per user / per parameter" case, declare a SpEL expression on the annotation:

```java
@GetMapping("/users/{id}")
@RateLimit(limit = 10, key = "#id")       // each id gets its own 10/window quota
public User get(@PathVariable Long id) { ... }
```

- Available variables: method parameters (referenced by name) and `#apiKey`;
- Final key = `fully.qualified.ClassName#method:expression-result`;
- Evaluation runs in a restricted `SimpleEvaluationContext`: **no** type references, constructor calls
  or bean references;
- Parse/evaluation failures fall back to endpoint-level limiting (warn logged), never breaking requests;
- For complex keys (headers, security context) prefer a `RateLimitKeyResolver` bean.

### 6) Custom rejection handling (new in 0.2.0)

Register a `RateLimitRejectHandler` bean to customize rejection behaviour:

```java
@Bean
public RateLimitRejectHandler rejectHandler() {
    return (context, rateLimitKey) -> {
        context.setRejectStatus(429);
        context.setRejectReason("Max " + context.getRateLimit() + " req/s");
        context.setAttribute("retryAfterSeconds", context.getWindow());
    };
}
```

If the handler throws, the filter falls back to the default (YAML-configured) rejection and the
short-circuit semantics are preserved.

### 7) Redis failure policy (new in 0.2.0)

```yaml
api:
  governance:
    rate-limit:
      type: redis
      fail-strategy: open    # open = allow on failure (default) / close = 503 reject on failure
```

Either way the failure is logged (error) and a `RATE_LIMITER_FAILURE` alert is published if a notifier
is configured. `fail-close` rejections return 503, distinct from regular 429 rate-limit rejections.

---

## 5. Filter Pipeline (custom plugins)

Implement `PreFilter` or `PostFilter` and register the bean; it joins the pipeline automatically
(sorted by `order`):

```java
@Component
@Order(300)
public class ParamCheckFilter implements PreFilter {
    @Override
    public boolean doFilter(FilterContext ctx) {
        if (ctx.getArgs() == null || ctx.getArgs().length == 0) {
            ctx.setRejectStatus(400);
            ctx.setRejectReason("Missing parameters");
            return false;   // short-circuit; business method is not invoked
        }
        return true;
    }
}
```

Built-in filters:

| Phase | Order | Filter | Responsibility |
|-------|-------|--------|----------------|
| Pre | 1 | MetadataCollectorFilter | metadata (HTTP method / path) |
| Pre | 100 | TrafficStatisticsFilter | traffic counters |
| Pre | 200 | RateLimitFilter | rate-limit decision |
| Post | 400 | SlowMethodFilter | elapsed time + stats + slow-method alert |
| Post | 500 | LoggingFilter | request logging |

---

## 6. Async Method Lifecycle Hooks

The target method stays synchronous; the framework only submits side tasks at lifecycle phases:

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
        // write audit log, send notifications, update non-critical stats
    }
}
```

Phases: `BEFORE`, `AFTER_SUCCESS`, `AFTER_ERROR`, `AFTER_COMPLETION`. Handlers are asynchronous by
default and never alter business results. Only immutable event snapshots cross threads — full
parameters, return values and original exceptions are not captured by default. See
[ASYNC_ACTIONS.md](ASYNC_ACTIONS.md).

---

## 7. In-Memory Metrics

Metrics live in memory (created with the process, destroyed with it, never persisted). Memory safety:

- Per-API "recent requests" use a **bounded sliding window** (`window-size` entries / `window-seconds`);
- Global API count is capped by `max-apis` with LRU eviction.

Recorded: total / success / fail / rejected / slow counts, min / max / avg elapsed time and recent records.

---

## 8. Micrometer Bridge (new in 0.2.0)

Governance events are mirrored to standard Micrometer metrics whenever a `MeterRegistry` bean exists
(`api.governance.metrics.micrometer-enabled` to disable):

| Metric | Type | Tags | Description |
|--------|------|------|-------------|
| `api.governance.requests` | Counter | api, method, outcome | outcome ∈ success/error/reject |
| `api.governance.request.duration` | Timer | api, method | duration distribution (excludes rejected) |
| `api.governance.apis.tracked` | Gauge | — | APIs currently tracked |

```yaml
management:
  endpoints:
    web:
      exposure:
        include: prometheus   # expose /actuator/prometheus
```

> The `api` tag is `ClassName#method` — bounded by the number of controller methods, so no tag
> explosion. Meters are permanent in the Micrometer registry; the in-memory registry's LRU eviction
> and DELETE endpoints do not remove them.

---

## 9. Alerting Plugin (new in 0.2.0)

Slow methods, rate-limit rejections and limiter failures can be delivered to custom notifiers:

```java
@Component
public class MyAlertNotifier implements GovernanceAlertNotifier {

    @Override
    public void notify(GovernanceAlertEvent event) {
        // metadata only: type / apiKey / path / elapsedMs / message / timestamp
        // invoked synchronously on the request thread — move slow IO off-thread yourself
    }
}
```

The built-in `WebhookAlertNotifier` (zero extra dependencies, async JDK HttpClient) targets
DingTalk / WeCom / Feishu bots:

```yaml
api:
  governance:
    alert:
      enabled: true
      suppress-interval-ms: 10000   # one alert per (type, apiKey) per 10s, storm protection
      webhook:
        enabled: true
        url: "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=xxx"
```

The shared dispatcher suppresses storms and isolates failures: any notifier exception is logged (warn)
and never affects requests. Events carry no method arguments, return values or stack traces.

---

## 10. Admin Endpoints

Base path defaults to `/api-governance` (configurable):

| Method | Path | Description |
|--------|------|-------------|
| GET | `/status` | governance status |
| GET | `/config` | current configuration |
| GET | `/filters` | filter chain info |
| GET | `/rate-limiter/status` | limiter status |
| GET | `/rate-limiter/count?key=` | current count for a key |
| POST | `/rate-limiter/reset?key=` | reset a key |
| POST | `/rate-limiter/reset-all` | reset all |
| GET | `/metrics` | all API metrics summary |
| GET | `/metrics/detail?key=` | single API detail (recent records) |
| GET | `/metrics/slow?key=` | slow requests for one API |
| GET | `/metrics/slow/all` | slow requests aggregated across APIs |
| DELETE | `/metrics` | clear all metrics |
| DELETE | `/metrics/single?key=` | clear one API's metrics |

> `key` is `fully.qualified.ClassName#method`, e.g. `com.example.UserController#get`.
> Protect these endpoints in production — the built-in token auth (new in 0.2.0) is the simplest option:

```yaml
api:
  governance:
    management:
      auth-token: ${GOVERNANCE_TOKEN}   # inject via environment variable; non-empty enables auth
      auth-header: X-Governance-Token
```

When enabled, requests must carry the matching token header or receive 401 (constant-time comparison,
timing-safe). Without a token, behaviour is identical to 0.1.0.

---

## 11. Distributed Tracing

The starter bundles Micrometer Tracing, the OpenTelemetry bridge and the OTLP exporter. HTTP requests,
framework async tasks and host Spring Kafka / Spring AMQP components automatically propagate W3C
`traceparent` — business code never manages `traceId` manually.

Kafka and RabbitMQ dependencies remain optional: only the adapter matching the host's middleware
activates. Adapters only enhance existing `KafkaTemplate`, `RabbitTemplate` and listener containers —
they never create or replace connections, serialization or listener configuration.

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

```yaml
api:
  governance:
    tracing:
      enabled: true
      async-context-propagation: true
      kafka: true
      rabbit: true
```

```yaml
logging:
  pattern:
    correlation: "[${spring.application.name:},%X{traceId:-},%X{spanId:-}] "
```

MQ `traceparent` / `tracestate` / `baggage` are written to message headers; keep managing `messageId`
and `correlationId` independently — do not use `traceId` for consumer idempotency.

---

## 12. Dependencies

| Dependency | Purpose | Optional |
|------------|---------|----------|
| spring-boot-starter-aop | AOP + core container | No |
| spring-web | @RestController annotations | No |
| jakarta.servlet-api | admin auth filter (provided; supplied by host container at runtime) | No (not transitive) |
| spring-boot-starter-actuator | Observation, tracing, Micrometer metrics | No |
| micrometer-tracing-bridge-otel | OpenTelemetry bridge | No |
| opentelemetry-exporter-otlp | OTLP trace export | No |
| spring-kafka | Kafka tracing adapter | Yes |
| spring-rabbit | RabbitMQ tracing adapter | Yes |
| spring-boot-configuration-processor | YAML metadata | Yes |
| spring-boot-starter-data-redis | Redis rate limiting | Yes |

> No `spring-boot-starter-web` (no embedded server), no Lombok. Webhook alerting uses the JDK 17
> HttpClient (zero third-party dependencies). Redis is optional.

---

## 13. Upgrading from 0.1.0 to 0.2.0

All new features are **additive** and default to 0.1.0 behaviour — no configuration changes required.
Two internal changes worth knowing:

1. `RateLimitFilter` / `SlowMethodFilter` constructors gained nullable parameters — only affects manual
   instantiation; auto-configuration users are unaffected.
2. Redis limiters no longer swallow exceptions internally; the auto-configured `FailSafeRateLimiter`
   wrapper handles the failure policy — the default `fail-strategy=open` (allow on failure) matches
   0.1.0. Code instantiating Redis limiters directly now sees exceptions instead of `true`.

---

## 14. Docs

- [中文文档](./README.md)：Chinese version of this document.
- [ASYNC_ACTIONS.md](./ASYNC_ACTIONS.md)：async action contract.
- [examples/api-governance-example](./examples/api-governance-example)：minimal runnable demo.

## 15. Build

```bash
mvnw.cmd clean install     # Windows
./mvnw clean install       # Linux/macOS
```

---

## 16. License

Apache License 2.0. See [LICENSE](./LICENSE).
