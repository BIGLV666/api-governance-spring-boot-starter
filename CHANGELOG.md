# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)，版本号格式 `MAJOR.MINOR.PATCH`。
0.x 阶段 MINOR 版本可能包含行为调整，升级前请阅读[版本升级](docs/upgrade.md)。

## [1.0.0] - 2026-11-11

主题：Spring Boot 4.1 平台升级 + 并发/容量治理 + API 冻结与发布工程。

### 新增

- **并发上限（bulkhead）**：`@ConcurrencyLimit(permits = N)` 限制单接口同时在途请求数，
  超限立即拒绝（429 + `Retry-After`），不排队、不占线程；名额在 `finally` 归还，
  异常/超时路径不泄漏。
- **动态规则 `maxConcurrent`**：`>0` 覆盖、`0` 不干预、`-1` 关闭；写入 Redis/内存规则存储，
  管理接口 PUT/import/export/list/audit 与 `/rules/effective` 均支持。
- **请求体大小限制**：`body-size-limit.enabled=true` 时在反序列化前拒绝超大 body
  （已知 Content-Length 直接 413；分块传输按实际读取字节计数）。
- **可选 RFC 9457 错误体**：`problem-details.enabled=true` 时治理拒绝返回
  `application/problem+json`；默认关闭，保持原结构。
- **平台升级**：Spring Boot 4.1.2 / Spring Framework 7 / Jakarta EE 11 / Jackson 3；
  CI 矩阵 JDK 17/21/25。
- **发布工程**：CycloneDX SBOM（`target/bom.json`）、可复现构建
  （`project.build.outputTimestamp`）、`-Pcompat` japicmp 二进制兼容检查。

### 变更（1.0.0 为 major 版本）

- `RuleAuditEntry` 新增 `beforeMaxConcurrent` / `afterMaxConcurrent`。
- `RateLimitPolicyResolver.ApiKeyResolution` 新增 `targetClass` / `method`。
- `GovernanceExceptionHandler` 新增接收 `ApiGovernanceProperties` 的构造器（无参构造器保留）。
- `GovernanceAspect` 新增四参构造器（旧二参/三参构造器保留）。
- Actuator 健康 API 迁移至 `org.springframework.boot.health.contributor`；
  MockMvc 测试自动配置迁移至 `spring-boot-starter-webmvc-test`。

### 兼容性

- 运行期默认行为与 0.8.0 一致；并发限制默认开启但仅在注解/动态规则/全局默认命中时生效，
  body 限制与 problem details 默认关闭。
- 0.8.0 是最后一个 Boot 3.x 版本；1.0.0 只支持 Boot 4.1.x / Java 17+。

## [0.8.0] - 2026-11-06

主题：动态规则运维闭环（TTL / 解释 / 审计 / 导入导出 / 启动种子）+ Actuator 健康与 Info。

### 新增

- **规则 TTL 与操作元数据**：`PUT /rate-limiter/rules` 支持 `ttlSeconds`（缺省取
  `dynamic-rules.default-ttl-seconds`）与 `operator` / `reason`。到期后热路径惰性失效，
  无需后台线程；规则列表返回 `expiresAt` 与 `ttlSecondsRemaining`。
- **生效规则解释端点**：`GET /rate-limiter/rules/effective?apiKey=` 返回注解 / 全局默认 /
  动态规则各自的值、最终生效值与来源。切面与解释端点共用新增的 `RateLimitPolicyResolver`，
  确保「解释」与「执行」不漂移。
- **规则变更审计**：`GET /rate-limiter/rules/audit?limit=`，时间倒序，记录动作
  （PUT / DELETE / IMPORT / REPLACE / SEED）、变更前后值、操作人、来源 IP 与结果。
  新增 `RuleAuditLog` 插件接口，内置 `InMemoryRuleAuditLog` 有界环形缓冲
  （`audit-capacity`，默认 1000；0 = 关闭，可注册自定义 Bean 持久化）。
- **规则导入 / 导出**：`GET /rate-limiter/rules/export` 与
  `POST /rate-limiter/rules/import`（`mode=merge|replace`，支持 `dryRun`）。导入先整批校验，
  任一规则非法则全部不写入。
- **启动种子规则**：`dynamic-rules.seed-location` 指向 YAML 文件，所有单例就绪后写入存储；
  `seed-fail-fast`（默认 true）在文件/规则非法时启动失败，设为 false 则跳过并 warn。
- **Actuator 健康指示器**：`apiGovernance` 组件暴露限流器故障、规则存储健康/陈旧、
  异步线程池饱和与 warnings。`fail-strategy=open`（默认）时故障仍为 UP，`close` 时为 DOWN。
- **Actuator Info 贡献器**：`/actuator/info` 展示组件版本与限流/告警/追踪实际生效配置。
- **存储健康观测**：`FailSafeRateLimiter` 记录最近成功/失败时间；`RateRuleStore` 新增
  `isHealthy()` / `getLastSuccessfulRefreshMs()` / `getLastFailureMs()` 默认方法，
  `RedisRateRuleStore` 实现刷新健康统计。
- **批量写入接口**：`RateRuleStore.putAll` / `replaceAll` / `activeRules`（默认方法保持
  0.7.0 自定义实现兼容）。

### 变更

- `GovernanceManagementController` 新增八参构造器（含 `RuleAuditLog`），七参/六参/四参
  构造器保留并标注 `@Deprecated`（审计按关闭处理）。
- `DynamicRateRule` 新增 TTL / 元数据字段与工厂重载；原三参 `of` 保持永久规则语义。
- Redis 动态规则存储格式扩展为 `limit|window|updatedAtMs|expiresAtMs|operator|reason`，
  向后兼容 0.7.0 的三字段值；0.7.0 读取新格式时忽略末尾字段（不执行 TTL）。
- `ApiGovernanceProperties.Management` 新增 `health-enabled` / `health-stale-multiplier`；
  `DynamicRules` 新增 `default-ttl-seconds` / `audit-capacity` / `seed-location` / `seed-fail-fast`。

## [0.7.0] - 2026-10-04

- 动态限流规则热更新（本地内存 / Redis 全集群，尾部 `*` 通配，fail-stale）。
- 集群告警去重（Redis `SET NX`，fail-open）。
- Spring Boot 基线升至 3.5.16，兼容 3.3 / 3.4 / 3.5 与 JDK 17/21。

## [0.6.0] - 2026-09-28

- 标准限流响应头（`RateLimit-*` / `Retry-After`）。
- 告警恢复通知（限流器故障恢复、慢方法安静窗口）。
- Grafana 面板与 Redis 集群限流回归验证。

## [0.5.1] - 2026-09-28

- 限流器装配完整性守卫：`type=redis` 但容器无 `RateLimiter` 时启动失败。
- `spring-boot-starter-actuator` 改为可选依赖。

## [0.5.0] - 2026-09-02

- 异步方法钩子可观测性：执行指标、管理端点、队列拒绝告警、启动期 action 交叉校验、
  HTTP 上下文快照 enricher。

## [0.4.0]

- 治理范围 `include-packages` / `exclude-packages`。
- 指标分页、写操作开关 `mutations-enabled`、链路追踪依赖改为可选。
- Redis 限流 Lua 使用服务器时间；`resetAll` 改为 `SCAN`。

## [0.3.0]

- 内置过滤器可插拔、HTTP 请求上下文、告警平台原生格式（钉钉加签 / 企微 / 飞书）、
  管理配置敏感字段掩码。

## [0.2.0] - 2026-08-29

- SpEL 参数维度限流、自定义拒绝处理器、限流故障降级（open/close）、Micrometer 桥接、
  告警插件与内存指标。

## [0.1.0] - 2026-08-24

- 首个版本：Controller 切面、管道过滤器、本机 / Redis 限流、日志与指标、后台管理接口。
