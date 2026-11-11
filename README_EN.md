# API Governance Spring Boot Starter

[![Maven Central](https://img.shields.io/maven-central/v/io.github.biglv666/api-governance-spring-boot-starter)](https://central.sonatype.com/artifact/io.github.biglv666/api-governance-spring-boot-starter) [![CI](https://github.com/BIGLV666/api-governance-spring-boot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/BIGLV666/api-governance-spring-boot-starter/actions/workflows/ci.yml)

> **Core philosophy: everything is a plugin** — a lightweight, batteries-included API governance starter with pluggable everything.

[中文](README.md) | English

Governance defaults to intercepting every Controller request through a single AOP aspect driven by a
**standard pipeline of filters** (pre-chain + post-chain): rate limiting, logging, metrics and more.
Built-in local / Redis rate limiting with token-bucket / sliding-window algorithms, custom algorithm
strategies, admin endpoints — with **no heavy external dependencies**.

---

## Features

- ✅ **Intercepts all Controller requests by default** — no annotations required (governance = logging + metrics).
- ✅ **Minimal annotations** — only `@RateLimit` / `@Skip` / `@NoLog`, to override defaults where needed.
- ✅ **Standard filter pipeline** — pre-chain short-circuits (metadata → traffic stats → rate limit → custom),
  post-chain always runs (elapsed time → stats → logging → custom); `pjp.proceed()` is invoked exactly once.
- ✅ **Two storages, two algorithms** — local (zero dependency) and Redis (distributed, atomic Lua);
  token bucket and sliding window; replace them with a `RateLimiter` or `RateLimitStrategy` bean.
- ✅ **SpEL per-parameter limiting** — `@RateLimit(key = "#userId")` gives each parameter value its own
  quota, evaluated in a restricted, security-hardened context.
- ✅ **Failure policy** — configurable fail-open (allow) / fail-close (503) when Redis is down, with alerting.
- ✅ **Standard rate-limit headers** — 429 responses carry `RateLimit-Limit` / `RateLimit-Remaining` /
  `RateLimit-Reset` / `Retry-After`.
- ✅ **Dynamic rules** — submit rules at runtime (trailing-`*` patterns) without restart; TTL expiry,
  effective-rule explanation, audit, import/export and startup seeds (rules as code).
- ✅ **Micrometer + Grafana** — metrics on `/actuator/prometheus`, plus a ready-to-import 10-panel dashboard.
- ✅ **Alerting plugin** — slow-method / rejection / limiter-failure events to custom notifiers; built-in
  webhook for DingTalk (incl. signing) / WeCom / Feishu, storm suppression, recovery notices and cluster dedup.
- ✅ **Bean or YAML configuration** — register beans to override, or just configure YAML defaults.
- ✅ **Bounded in-memory metrics** — sliding windows and LRU eviction keep memory flat.
- ✅ **Admin endpoints** — query & reset for ops tooling, optional static-token auth and read-only mode.
- ✅ **Async method hooks** — `@AsyncAction` + `@AsyncHandler` four-phase side tasks, with metrics,
  pool gauges, rejection alerts and startup cross-validation.
- ✅ **Configurable scope** — `include-packages` / `exclude-packages` per package prefix.
- ✅ **Optional tracing** — no OpenTelemetry stack required unless you want trace reporting.
- ✅ **Actuator health & info** — limiter failures, stale rule stores and pool saturation on `/actuator/health`;
  effective configuration on `/actuator/info`.
- ✅ **Lightweight** — Redis / MQ / Actuator are optional; no Lombok; webhook uses the JDK HttpClient.
- ✅ **Boot 3.3–3.5** — Spring Boot 3.5 baseline, JDK 17/21.

---

## Quick Start

### 1) Add the dependency

```xml
<dependency>
    <groupId>io.github.biglv666</groupId>
    <artifactId>api-governance-spring-boot-starter</artifactId>
    <version>0.5.0</version>
</dependency>
```

> The snippet above uses **0.5.0**, the latest version published on Maven Central. The repository
> source is at **0.8.0**; 0.6.0–0.8.0 are not released yet. Build from source and install locally if
> you need the newer capabilities.

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

The main configuration entry is `api.governance.*` in `application.yml`
(see [Configuration](docs/configuration.md)). The default `default-limit: -1` means the pipeline
intercepts and measures but never rejects.

---

## Documentation

| Document | Content |
|----------|---------|
| [Configuration](docs/configuration.md) | All `api.governance.*` properties and defaults |
| [Rate limiting](docs/rate-limiting.md) | Local / Redis, algorithms, SpEL keys, headers, failure policy, dynamic rules |
| [Filter pipeline](docs/filters.md) | Custom `PreFilter` / `PostFilter` plugins and built-in filters |
| [Async hooks](docs/async.md) | `@AsyncAction` / `@AsyncHandler` and observability ([contract](docs/ASYNC_ACTIONS.md)) |
| [Observability](docs/observability.md) | In-memory metrics, Micrometer, Grafana, alerts, Actuator health/info |
| [Management API](docs/management-api.md) | Endpoints, rule explain/audit/import/export, auth, mutation switch |
| [Tracing](docs/tracing.md) | OpenTelemetry integration and optional dependencies |
| [Dependencies](docs/dependencies.md) | Dependency matrix and compatibility range |
| [Upgrade notes](docs/upgrade.md) | Migration notes for each version |
| [CHANGELOG](CHANGELOG.md) | Changes by release |
| [Fault drills](drills/README.md) | Redis failure / pool saturation / alert lifecycle (reproducible) |
| [Runnable example](examples/api-governance-example) | Minimal demo project (with drill profile) |
| [Full English guide](docs/guide-en.md) | Detailed English usage guide |
| [中文文档](README.md) | Chinese README |

## License

Apache License 2.0. See [LICENSE](./LICENSE).
