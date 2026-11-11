# API Governance — Full English Guide

> This file preserves the detailed English usage sections previously in `README_EN.md`,
> now moved under `docs/`. For the Chinese entry point see [README.md](../README.md);
> section-level documents are listed at the end.

## 1. Configuration (application.yml)

```yaml
api:
  governance:
    enabled: true                     # master switch (default true)
    include-packages: []              # governance scope prefixes (new in 0.4.0, empty = all)
    exclude-packages: []              # excluded prefixes (new in 0.4.0, takes precedence)
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
      max-entries: 10000              # max limiter keys (lower it for high-cardinality SpEL keys)
      status-code: 429
      message: "Too many requests, please retry later"
      fail-strategy: open             # limiter failure: open=allow / close=503 (applies to Redis)
      dynamic-rules:                  # new in 0.7.0: dynamic rate-limit rules
        enabled: true                 # master switch (no rules = zero behavior change)
        refresh-interval-ms: 5000     # redis mode version poll interval (cluster propagation bound)
        max-rules: 1000               # rule count cap
        default-ttl-seconds: 0        # new in 0.8.0: default rule TTL (0 = permanent)
        audit-capacity: 1000          # new in 0.8.0: in-memory audit ring buffer (0 = disabled)
        seed-location: ""             # new in 0.8.0: startup seed YAML (classpath:/file:)
        seed-fail-fast: true          # new in 0.8.0: invalid seed fails startup (false = skip + warn)
    concurrency-limit:                # new in 1.0.0: in-flight request cap (bulkhead)
      enabled: true                   # applies when annotation/dynamic rule/global default permits > 0
      default-permits: -1             # global default (-1 = unlimited)
      max-entries: 10000              # local semaphore key cap
      idle-timeout-ms: 600000         # idle bucket eviction
      reject-status: 429
      message: "Too many concurrent requests, please retry later"
    body-size-limit:                  # new in 1.0.0: request body size limit (off by default)
      enabled: false
      max-bytes: 1048576              # 1 MiB
      reject-status: 413
      message: "Request body exceeds the size limit"
    problem-details:                  # new in 1.0.0: RFC 9457 application/problem+json (off by default)
      enabled: false
    filters:                          # built-in filter switches (new in 0.3.0); same-type beans also override
      metadata-collector: true
      traffic-statistics: true
      rate-limit: true
      slow-method: true
      logging: true
    metrics:
      window-size: 100
      window-seconds: 300
      max-apis: 1000                  # LRU eviction beyond this
      micrometer-enabled: true        # bridge to Micrometer when a MeterRegistry exists
    alert:
      enabled: true
      suppress-interval-ms: 10000     # min interval per (type, apiKey) to prevent alert storms
      cluster-dedup-enabled: false    # new in 0.7.0: cluster-wide alert dedup (needs Redis, SET NX)
      webhook:
        enabled: false
        url: ""                       # webhook endpoint (DingTalk/WeCom/Feishu bot)
        timeout-ms: 3000
        platform: generic             # generic / dingtalk / wecom / feishu (new in 0.3.0)
        sign-secret: ""               # DingTalk signing secret (dingtalk only; inject via ${DINGTALK_SECRET})
        secret-token: ""              # optional, sent as X-Governance-Token header
    management:
      enabled: true
      base-path: /api-governance
      mutations-enabled: true         # write-endpoint switch (new in 0.4.0; false disables reset/delete)
      auth-token: ""                  # non-empty enables token auth for admin endpoints
      auth-header: X-Governance-Token
      health-enabled: true            # new in 0.8.0: register /actuator/health indicator when actuator is present
      health-stale-multiplier: 3      # new in 0.8.0: stale threshold = refresh-interval-ms × this value
    async:
      enabled: true
      core-pool-size: 2
      max-pool-size: 8
      queue-capacity: 1000
      keep-alive-seconds: 60
      thread-name-prefix: api-governance-async-
      await-termination-seconds: 5
      ignore-unmatched-handlers: false # new in 0.5.0: true = warn only for unknown actions (default: fail-fast)
      web-context-enrichment: true     # new in 0.5.0: snapshot requestUri/httpMethod/clientIp into event data
```

> By default `default-limit: -1` (no limiting): the pipeline intercepts and measures but never rejects.
> Set it to a positive value for global limiting; individual endpoints can override via `@RateLimit`.

---

## 2. Rate Limiting

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

### 8) Dynamic rules, TTL and audit (new in 0.8.0)

Rules submitted through `PUT /rate-limiter/rules` accept an optional `ttlSeconds`; once expired the
rule stops matching on the request hot path (lazy expiry, no background thread) — a temporary ban or
tightening lifts itself. `operator` (header `X-Governance-Operator` or a JSON field) and `reason` are
stored alongside the rule and returned by the list/export endpoints:

```bash
curl -X PUT http://host:8080/api-governance/rate-limiter/rules \
  -H "Content-Type: application/json" -H "X-Governance-Operator: alice" \
  -d '{"pattern": "com.x.OrderController#create", "limit": 10,
       "ttlSeconds": 300, "reason": "incident mitigation"}'
```

- `GET /rate-limiter/rules/effective?apiKey=` shows the annotation/global/dynamic values, the final
  effective value and its source (`dynamic-rule` / `method-annotation` / `class-annotation` /
  `global-default` / `none`). The aspect and this endpoint share `RateLimitPolicyResolver`, so the
  explanation cannot drift from execution.
- `GET /rate-limiter/rules/audit` returns rule changes (PUT/DELETE/IMPORT/REPLACE/SEED) with
  before/after values, operator, client IP and result. Backed by a bounded in-memory ring buffer
  (`audit-capacity`, default 1000; 0 disables it).
- `GET /rate-limiter/rules/export` and `POST /rate-limiter/rules/import` (`mode=merge|replace`,
  `dryRun=true` supported) move rule sets between environments. Import validates the whole batch
  first — one invalid rule means nothing is written.
- `dynamic-rules.seed-location` loads a YAML rule file at startup (after all singletons are ready),
  for rules-as-code and disaster recovery. `seed-fail-fast` (default true) fails startup on an invalid
  file/rule; set it to false to skip and warn.

```yaml
# governance-rules.yml
rules:
  - pattern: com.x.OrderController#create
    limit: 100
    window: 60
    ttlSeconds: 300
    operator: ops
    reason: campaign tightening
```

### 9) Concurrency bulkhead (new in 1.0.0)

Rate limiting caps requests per second but not in-flight requests; a slow downstream can still
occupy every worker thread. `@ConcurrencyLimit` caps simultaneous executions and rejects excess
requests immediately (no queueing, no thread occupied):

```java
@PostMapping("/orders")
@ConcurrencyLimit(permits = 10)   // at most 10 in-flight requests
public Order create(@RequestBody Order order) { ... }
```

Dynamic rules expose the same control through `maxConcurrent` (`>0` cap, `0` no override, `-1`
disable), so an incident can tighten or lift it without a restart. Permits are released in
`finally` — exceptions and timeouts cannot leak them. Default rejection is 429 with
`Retry-After: 1`. The bulkhead is per API and process-local in 1.0.0 (no Redis lease); Spring MVC
async return values (`Callable`/`DeferredResult`) release the permit when the handler returns, so
use synchronous handlers where the cap must cover the actual work.

Optional overload protection:

```yaml
api:
  governance:
    body-size-limit:
      enabled: true
      max-bytes: 1048576        # 413 before deserialization
    problem-details:
      enabled: true             # RFC 9457 application/problem+json
```

Length-unknown form/multipart bodies are rejected (the container parses them before the wrapper
can count bytes); send `Content-Length` for those. See [concurrency.md](concurrency.md).

---

## 3. Filter Pipeline (custom plugins)

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

Built-in filters (since 0.3.0 each can be disabled via `api.governance.filters.*` or overridden by a
same-type bean):

| Phase | Order | Filter | Responsibility |
|-------|-------|--------|----------------|
| Pre | 1 | MetadataCollectorFilter | metadata (real request URI/method, falls back to annotations) |
| Pre | 100 | TrafficStatisticsFilter | traffic counters |
| Pre | 200 | RateLimitFilter | rate-limit decision |
| Post | 400 | SlowMethodFilter | elapsed time + stats + slow-method alert |
| Post | 500 | LoggingFilter | request logging |

Since 0.3.0 `FilterContext` exposes real HTTP request info: `getRequestUri()` (actual URI including path
variables), `getClientIp()` (`X-Forwarded-For` → `X-Real-IP` → `remoteAddr`), and `getHttpMethod()` /
`getPath()` (real values take precedence). `clientIp` can be forged via headers — use it for stats and
alerting only, never for security decisions.

---

## 4. Async Method Lifecycle Hooks

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

### Async observability (new in 0.5.0)

- **Startup fail-fast**: an `@AsyncHandler` referencing a non-existent action fails startup
  (downgradable to a warning via `ignore-unmatched-handlers: true`);
- **Metrics**: `api.governance.async.executions` (Counter), `api.governance.async.execution.duration`
  (Timer), `api.governance.async.pool.active` / `queue.size` (Gauges) — registered when a
  `MeterRegistry` exists;
- **Alerting**: queue rejections publish an `ASYNC_TASK_REJECTED` alert (storm suppression reused);
- **Admin endpoints**: `GET /async/handlers` (handler registry), `GET /async/status` (pool status);
- **HTTP context snapshot**: event `data` carries `requestUri` / `httpMethod` / `clientIp`
  of the current request (optional).

---

## 5. In-Memory Metrics

Metrics live in memory (created with the process, destroyed with it, never persisted). Memory safety:

- Per-API "recent requests" use a **bounded sliding window** (`window-size` entries / `window-seconds`);
- Global API count is capped by `max-apis` with LRU eviction.

Recorded: total / success / fail / rejected / slow counts, min / max / avg elapsed time and recent records.

---

## 6. Micrometer Bridge (new in 0.2.0)

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

## 7. Alerting Plugin (new in 0.2.0)

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

The built-in `WebhookAlertNotifier` (zero extra dependencies, async JDK HttpClient) natively targets
DingTalk / WeCom / Feishu bots since 0.3.0, including DingTalk signing:

```yaml
api:
  governance:
    alert:
      enabled: true
      suppress-interval-ms: 10000   # one alert per (type, apiKey) per 10s, storm protection
      webhook:
        enabled: true
        platform: dingtalk            # generic / dingtalk / wecom / feishu
        url: "https://oapi.dingtalk.com/robot/send?access_token=xxx"
        sign-secret: ${DINGTALK_SECRET}   # required when the bot enables "signing" security
```

- `dingtalk` / `wecom`: native `{"msgtype":"text","text":{"content":...}}` payload;
- `feishu`: native `{"msg_type":"text","content":{"text":...}}` payload;
- `dingtalk` with `sign-secret`: appends `timestamp` and `sign` (HMAC-SHA256 + Base64) to the URL;
- `generic` (default): the framework's own JSON format (0.2.0 behavior) with full event metadata.

The shared dispatcher suppresses storms and isolates failures: any notifier exception is logged (warn)
and never affects requests. Events carry no method arguments, return values or stack traces.

---

## 8. Admin Endpoints

Base path defaults to `/api-governance` (configurable):

| Method | Path | Description |
|--------|------|-------------|
| GET | `/status` | governance status |
| GET | `/config` | current configuration (sensitive fields masked since 0.3.0) |
| GET | `/filters` | filter chain info |
| GET | `/rate-limiter/status` | limiter status |
| GET | `/rate-limiter/count?key=` | current count for a key |
| POST | `/rate-limiter/reset?key=` | reset a key |
| POST | `/rate-limiter/reset-all` | reset all |
| GET | `/rate-limiter/rules` | dynamic rate-limit rules list (new in 0.7.0; TTL/metadata since 0.8.0) |
| PUT | `/rate-limiter/rules` | upsert one dynamic rule, effective immediately (new in 0.7.0; `ttlSeconds`/`operator`/`reason` since 0.8.0, `maxConcurrent` since 1.0.0) |
| DELETE | `/rate-limiter/rules?pattern=` | delete one dynamic rule (idempotent, new in 0.7.0) |
| GET | `/rate-limiter/rules/effective?apiKey=` | explain the effective policy and its source (new in 0.8.0) |
| GET | `/rate-limiter/rules/audit?limit=` | rule change audit, newest first (new in 0.8.0) |
| GET | `/rate-limiter/rules/export` | export all unexpired rules (new in 0.8.0) |
| POST | `/rate-limiter/rules/import` | bulk import/replace with `dryRun` (new in 0.8.0) |
| GET | `/metrics` | all API metrics summary (supports `page`/`size` since 0.4.0) |
| GET | `/async/handlers` | registered async handler list (new in 0.5.0) |
| GET | `/async/status` | async thread-pool status (new in 0.5.0) |
| GET | `/metrics/detail?key=` | single API detail (recent records) |
| GET | `/metrics/slow?key=` | slow requests for one API |
| GET | `/metrics/slow/all` | slow requests aggregated across APIs |
| DELETE | `/metrics` | clear all metrics |
| DELETE | `/metrics/single?key=` | clear one API's metrics |

> `key` is `fully.qualified.ClassName#method`, e.g. `com.example.UserController#get`.
> `GET /config` masks sensitive fields (`management.auth-token`, `alert.webhook.secret-token`,
> `alert.webhook.sign-secret`) as `******` when non-empty. Note that `alert.webhook.url` is returned
> as-is — DingTalk/WeCom bot URLs may embed an `access_token` in the query string; exposing it is the
> operator's decision, so enable the built-in token auth below in production.
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

## 9. Distributed Tracing

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

## 10. Dependencies

| Dependency | Purpose | Optional |
|------------|---------|----------|
| spring-boot-starter-aspectj | AOP + core container (renamed from starter-aop in Boot 4) | No |
| spring-web | @RestController annotations | No |
| jakarta.servlet-api | admin auth filter (provided; supplied by host container at runtime) | No (not transitive) |
| spring-boot-starter-actuator | Observation, tracing, Micrometer metrics, health/info (since 0.8.0) | Yes |
| micrometer-tracing-bridge-otel | OpenTelemetry bridge (optional since 0.4.0) | Yes |
| opentelemetry-exporter-otlp | OTLP trace export (optional since 0.4.0) | Yes |
| spring-kafka | Kafka tracing adapter | Yes |
| spring-rabbit | RabbitMQ tracing adapter | Yes |
| spring-boot-configuration-processor | YAML metadata | Yes |
| spring-boot-starter-data-redis | Redis rate limiting | Yes |

> No `spring-boot-starter-web` (no embedded server), no Lombok. Webhook alerting uses the JDK 17
> HttpClient (zero third-party dependencies). Redis is optional.
> **1.0.0 compatibility:** Spring Boot 4.1.x, JDK 17–25 (Spring Framework 7 / Jakarta EE 11 / Jackson 3).

---

## 11. Version Upgrades

### Upgrading from 0.8.0 to 1.0.0

1.0.0 is a platform major release and **requires Spring Boot 4.1.x / Java 17+**:

1. **Platform**: Boot 3.5 → 4.1 (Spring Framework 7, Jakarta EE 11, Jackson 3). Hosts using
   Jackson 2 directly must migrate to `tools.jackson.*`;
2. **Starter renames**: `spring-boot-starter-aop` → `spring-boot-starter-aspectj`; MockMvc test
   auto-configuration moved to `spring-boot-starter-webmvc-test`;
3. **Actuator health package**: `org.springframework.boot.actuate.health.*` →
   `org.springframework.boot.health.contributor.*`;
4. **Concurrency governance is on by default but inert** unless an annotation, dynamic rule or
   positive global default applies. Custom `RateRuleStore` implementations that only implement the
   three-argument `put` reject non-zero `maxConcurrent` instead of silently dropping it;
5. **Minor public type changes**: `RuleAuditEntry` gained
   `beforeMaxConcurrent` / `afterMaxConcurrent`; `ApiKeyResolution` gained `targetClass` / `method`;
   `GovernanceExceptionHandler` gained a constructor accepting properties; `GovernanceAspect` gained
   a four-argument constructor (older constructors kept).

`body-size-limit` and `problem-details` are off by default. 0.8.0 is the last Boot 3.x release.

### Upgrading from 0.7.0 to 0.8.0

All changes are additive and defaults preserve 0.7.0 behaviour:

1. **Actuator health/info beans are registered by default** when actuator is on the classpath. With
   the default `fail-strategy=open`, failures still report UP, so existing probes are unaffected.
   Set `api.governance.management.health-enabled=false` to opt out;
2. **Rule audit is on by default** (`dynamic-rules.audit-capacity=1000`, bounded ring buffer);
   set it to 0 to disable;
3. **`RateRuleStore` gained default methods**: existing custom implementations compile and run
   unchanged, but they lose TTL/metadata unless they override `put(DynamicRateRule)` / `putAll` /
   `replaceAll`. The Redis format stays backward compatible (0.8.0 reads 0.7.0's three-field values;
   0.7.0 ignores the new trailing fields, so it does not apply TTL during a rolling upgrade);
4. **New eight-argument `GovernanceManagementController` constructor**: the 7/6/4-argument variants
   remain available and are deprecated (audit treated as disabled).

Additive items: rule TTL and operator/reason metadata; effective-rule explain endpoint; audit
endpoint; export/import (with `dryRun`); startup seed rules (`seed-location`, `seed-fail-fast`).

### Upgrading from 0.6.0 to 0.7.0

All changes are additive, with three behavioral notes:

1. **Spring Boot baseline moved from 3.2.0 to 3.5.16**: this starter's full suite passes; host
   applications should follow Boot's own migration notes for 3.3/3.4/3.5. The declared support range
   is Boot 3.3 – 3.5 (CI matrix covers 3.3.13 / 3.4.13 / 3.5.16 × JDK 17/21);
2. **Dynamic rate-limit rules are enabled by default**
   (`api.governance.rate-limit.dynamic-rules.enabled: true`): with no rules submitted the behaviour is
   byte-identical to 0.6.0; disable explicitly if you do not want the rules endpoints exposed;
3. **One background daemon thread in Redis mode** (`dynamic-rules` on, `type=redis`): polls the rule
   version once per `refresh-interval-ms`. Disable `dynamic-rules` to keep 0.6.0 connection behaviour.

Additive items: three admin endpoints `GET/PUT/DELETE /rate-limiter/rules` (behind the existing
`auth-token` auth and `mutations-enabled` switch), the `alert.cluster-dedup-enabled` option (default
off, requires Spring Data Redis on the classpath), and new constructor overloads on
`GovernanceManagementController` / `AlertDispatcher` (old signatures deprecated but still available).

### Upgrading from 0.4.0 to 0.5.0

All changes are additive, with two behavioral notes:

1. **Startup cross-validation defaults to fail-fast**: an `@AsyncHandler` referencing a non-existent
   `@AsyncAction` now fails startup (previously it silently never executed). Set
   `api.governance.async.ignore-unmatched-handlers: true` to downgrade to a warning;
2. Async event `data` gains three read-only keys by default — `requestUri` / `httpMethod` / `clientIp`
   (disable via `web-context-enrichment: false`). No impact on existing handlers.

Metrics, admin endpoints and the new alert type are purely additive.

### Upgrading from 0.3.0 to 0.4.0

All new configuration defaults keep the 0.3.0 behaviour — no configuration changes required. Two notes:

1. `micrometer-tracing-bridge-otel` and `opentelemetry-exporter-otlp` are now **optional dependencies**:
   if your build relied on receiving them transitively through this starter, declare them explicitly
   or trace reporting silently turns off (all other governance capabilities are unaffected);
2. The Redis limiter Lua scripts now take the time from the Redis server (`TIME` command) for window
   and token-refill decisions — clock drift across instances no longer affects limiting accuracy.
   `TIME` is non-deterministic, so the scripts explicitly call `redis.replicate_commands()` to switch
   to effect replication: Redis 3.2–4.x (verbatim script replication) is supported as well, and on
   Redis 5+ the call is a harmless no-op (effect replication is already the default).

### Upgrading from 0.2.0 to 0.3.0

All new configuration defaults keep the 0.2.0 behaviour — no configuration changes required. Three notes:

1. `GET /api-governance/config` masks sensitive fields (`auth-token`, `secret-token`, `sign-secret`) as
   `******` when non-empty — this fixes a sensitive-information leak; tools relying on plaintext must
   read the values from environment variables instead.
2. `path` / `httpMethod` in the filter context are now the **real request values** (falling back to
   annotation-derived patterns outside a Servlet environment).
3. The Redis sliding-window member changed from `millis-threadId` to a UUID, removing a lost-count issue
   when the same thread sends two requests within the same millisecond; `resetAll` now uses batched
   `SCAN` instead of `KEYS`.

### Upgrading from 0.1.0 to 0.2.0

All new features are **additive** and default to 0.1.0 behaviour — no configuration changes required.
Two internal changes worth knowing:

1. `RateLimitFilter` / `SlowMethodFilter` constructors gained nullable parameters — only affects manual
   instantiation; auto-configuration users are unaffected.
2. Redis limiters no longer swallow exceptions internally; the auto-configured `FailSafeRateLimiter`
   wrapper handles the failure policy — the default `fail-strategy=open` (allow on failure) matches
   0.1.0. Code instantiating Redis limiters directly now sees exceptions instead of `true`.

---


---

## 12. Documentation

- [Chinese README](../README.md)
- [Configuration](configuration.md)
- [Rate limiting](rate-limiting.md)
- [Filter pipeline](filters.md)
- [Async actions](async.md) / [full contract](ASYNC_ACTIONS.md)
- [Observability](observability.md)
- [Management API](management-api.md)
- [Tracing](tracing.md)
- [Dependencies](dependencies.md)
- [Upgrade notes](upgrade.md)
- [CHANGELOG](../CHANGELOG.md)

## 13. License

Apache License 2.0. See [LICENSE](../LICENSE).
