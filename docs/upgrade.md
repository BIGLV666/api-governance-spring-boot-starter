# 版本升级

> 本文档为[项目主页](../README.md)的拆分章节；索引与快速开始见主页。

### 从 0.8.0 升级到 1.0.0

1.0.0 是平台大版本，**只支持 Spring Boot 4.1.x / Java 17+**。功能默认行为保持 0.8.0：

1. **平台升级**：Spring Boot 3.5 → 4.1（Spring Framework 7、Jakarta EE 11、Jackson 3）。
   宿主若直接使用 Jackson 2（`com.fasterxml.jackson.*`），需迁移到 Jackson 3
   （`tools.jackson.*`）；starter 主代码不直接依赖 Jackson，不受影响；
2. **Starter 名称变化**：Boot 4 将 `spring-boot-starter-aop` 更名为
   `spring-boot-starter-aspectj`，MockMvc 测试自动配置拆分到
   `spring-boot-starter-webmvc-test`。仅影响宿主显式声明这些依赖的场景；
3. **Actuator 健康 API 包名**：`org.springframework.boot.actuate.health.*` →
   `org.springframework.boot.health.contributor.*`。只影响自行实现/直接引用健康指示器的宿主；
   通过本 starter 自动装配的用户无感；
4. **并发治理默认开启但零行为变化**：只有标注 `@ConcurrencyLimit` 或动态规则设置
   `maxConcurrent` 时才生效；`body-size-limit` 与 `problem-details` 默认关闭。
   自定义 `RateRuleStore` 若只实现了 0.7.0 的三参 `put`，写入非零 `maxConcurrent`
   会被明确拒绝（而非静默丢弃）——需要并发语义请覆盖 `put(DynamicRateRule)`；
5. **少量公共类型签名变化**（1.0 为 major 版本）：
   - `RuleAuditEntry` 新增 `beforeMaxConcurrent` / `afterMaxConcurrent` 两个 record 组件；
   - `RateLimitPolicyResolver.ApiKeyResolution` 新增 `targetClass` / `method` 组件；
   - `GovernanceExceptionHandler` 新增接收 `ApiGovernanceProperties` 的构造器，
     无参构造器保留；`handleGovernanceException(GovernanceException)` 签名保持不变；
   - `GovernanceAspect` 新增四参构造器，旧二参/三参构造器保留。

新增能力：`@ConcurrencyLimit` 本地 bulkhead、动态规则 `maxConcurrent`、请求体大小限制
（413）、可选 RFC 9457 `application/problem+json`。详见[并发治理](concurrency.md)。

> **0.8.0 用户**：0.8.0 是最后一个 Boot 3.x 版本。继续使用 Boot 3.3–3.5 的宿主请停留在
> 0.8.0；升级到 1.0.0 需要同步升级到 Boot 4.1。

### 从 0.7.0 升级到 0.8.0

全部为增量特性，现有行为不变（默认值均已保持 0.7.0 语义）：

1. **新增 Actuator 健康/Info Bean（默认开启）**：类路径存在 actuator 时注册
   `apiGovernance` 健康指示器与 Info 贡献器。默认 `fail-strategy=open` 下故障仍返回 UP，
   不会改变现有探针结果；如不需要可设 `management.health-enabled=false`；
2. **规则审计默认开启**：内存环形缓冲 `dynamic-rules.audit-capacity=1000`，
   只记录规则变更（PUT/DELETE/IMPORT/REPLACE/SEED），容量到顶淘汰最旧条目；
   设为 0 即完全关闭；
3. **动态规则存储接口新增默认方法**：自定义 `RateRuleStore` 实现无需改动即可编译运行，
   但若需 TTL / 操作元数据 / 批量写入语义，请覆盖新的 `put(DynamicRateRule)` /
   `putAll` / `replaceAll` / `activeRules`（默认实现会退化为 0.7.0 三参写入）；
   Redis 存储格式向后兼容旧的三字段值，滚动升级期间新旧实例可共存；
4. **`GovernanceManagementController` 新增八参构造器**：七参/六参/四参构造器仍可用，
   已标注 `@Deprecated`（审计按关闭处理）。

增量项：规则 TTL 与操作元数据（`ttlSeconds` / `operator` / `reason`，规则列表返回
`expiresAt` 与 `ttlSecondsRemaining`）；`GET /rate-limiter/rules/effective` 解释端点；
`GET /rate-limiter/rules/audit` 审计端点；`GET /rate-limiter/rules/export` 与
`POST /rate-limiter/rules/import`（含 `dryRun`）；启动种子规则
`dynamic-rules.seed-location`（`seed-fail-fast` 默认 true）。

> **滚动升级提示**：Redis 规则值向后兼容（0.8.0 可读 0.7.0 的三字段值；0.7.0 读取
> 0.8.0 的六字段值会忽略末尾 TTL/元数据）。因此滚动升级期间 0.7.0 实例**不会执行 TTL
> 过期**、也不展示操作元数据；如需严格的 TTL 语义，请先升级全部实例再提交带 TTL 的规则。

### 从 0.6.0 升级到 0.7.0

全部为增量特性，默认行为需留意三点：

1. **Spring Boot 基线从 3.2.0 升至 3.5.16**：本 starter 自身测试全部通过；
   宿主应用从 Boot 3.2 升级时请同步核对 Boot 自身的迁移说明（3.3/3.4/3.5 各版本的
   deprecation 与行为变化），建议宿主直接跟随升级到 3.5.x。本 starter 声明的
   支持区间为 Boot 3.3 – 3.5（已验证 3.3.13 / 3.4.13 / 3.5.16 × JDK 17/21）；
2. **动态限流规则默认开启**（`api.governance.rate-limit.dynamic-rules.enabled: true`）：
   未通过管理接口提交过规则时行为与 0.6.0 完全一致（零规则零开销）；不希望暴露
   规则端点时可显式关闭；
3. **Redis 模式下新增两个后台连接**：`dynamic-rules` 开启时（Redis 限流模式）会启动
   一个 daemon 轮询线程（每 `refresh-interval-ms` 一次版本号 GET）—— 关闭
   `dynamic-rules` 即可回到 0.6.0 连接行为。

增量项：管理接口新增 `GET/PUT/DELETE /rate-limiter/rules` 三个端点（受既有
`auth-token` 鉴权与 `mutations-enabled` 写开关约束）；新增 `alert.cluster-dedup-enabled`
配置（默认关，开启需类路径存在 Spring Data Redis）；`GovernanceManagementController`
与 `AlertDispatcher` 新增构造重载，旧签名已标注 `@Deprecated` 但仍可用。

### 从 0.5.1 升级到 0.6.0

全部为增量特性，默认行为有两处需留意：

1. **429 响应新增标准限流头**：被限流的响应自动携带 `RateLimit-Limit` / `RateLimit-Remaining` /
   `RateLimit-Reset` / `Retry-After`。依赖精确响应头集合的客户端需知悉；
   自定义 `RateLimitRejectHandler` 可用 `context.addResponseHeader` 覆盖同名头；
2. **`spring-boot-starter-actuator` 转为可选依赖**（0.5.1 引入，此处重申）：需要 Micrometer
   指标桥接 / Prometheus 端点的宿主请显式声明该依赖，未引入时治理核心能力不受影响。

增量项：告警恢复通知（`api.governance.alert.recovery-enabled` 默认开，
关闭即回到 0.5.1 行为）；`resetAll` 的多节点扇出行为已通过 Redis 集群场景验证。

### 从 0.4.0 升级到 0.5.0

全部为增量特性，默认行为有两处需留意：

1. **启动期交叉校验默认 fail-fast**：`@AsyncHandler` 引用不存在的 `@AsyncAction` 时启动失败
   （0.4.0 及之前静默不执行）。存量应用若存在拼错的 action，升级后会启动失败——
   这正是该缺陷应当暴露的时机；临时放行可配置 `api.governance.async.ignore-unmatched-handlers: true`；
2. 异步事件的 `data` 默认新增 `requestUri` / `httpMethod` / `clientIp` 三个只读键
   （可通过 `web-context-enrichment: false` 关闭），对既有 handler 无影响。

其余（指标、管理端点、告警类型）均为纯增量。

### 从 0.3.0 升级到 0.4.0

所有新配置默认值均保持 0.3.0 行为，升级零配置即可完成。需要注意两点：

1. `micrometer-tracing-bridge-otel` 与 `opentelemetry-exporter-otlp` 改为**可选依赖**：
   若宿主依赖传递获得了这两个 jar 且依赖 starter 的传递引入，升级后需**显式声明**这两个依赖，
   否则链路上报将静默关闭（治理其余能力不受影响）；
2. Redis 限流 Lua 脚本改用 Redis 服务器时间（`TIME` 命令）判定窗口与令牌补充，
   多实例时钟漂移不再影响限流精度。`TIME` 属非确定性命令，脚本内已显式调用
   `redis.replicate_commands()` 切换为效果复制，Redis 3.2–4.x（逐字复制模式）同样可用；
   Redis 5+ 默认即效果复制，该调用幂等无害。

### 从 0.2.0 升级到 0.3.0

所有新配置默认值均保持 0.2.0 行为，升级零配置即可完成。需要注意三点：

1. `GET /api-governance/config` 的敏感字段（`auth-token`、`secret-token`、`sign-secret`）非空时返回
   `******` 而非明文 —— 这是敏感信息泄露修复，依赖明文输出的工具需改从环境变量读取；
2. 上下文中的 `path`/`httpMethod` 由「注解推导值」升级为「真实请求值」（无 Servlet 环境保持注解推导回退）；
3. Redis 滑动窗口 member 由「毫秒-线程ID」改为 UUID，消除了同毫秒同线程请求计数被覆盖的偏松问题；
   `resetAll` 从 `KEYS` 改为 `SCAN` 分批执行。

### 从 0.1.0 升级到 0.2.0

所有新特性均为**增量**且默认保持 0.1.0 行为，升级零配置即可完成。需要注意的两点内部变化：

1. `RateLimitFilter` / `SlowMethodFilter` 构造签名新增可空参数 —— 仅影响手动 `new` 的场景，
   自动装配用户无感；
2. Redis 限流器内部不再自行 catch 异常，由自动配置统一包装的 `FailSafeRateLimiter` 处理降级 ——
   默认 `fail-strategy=open`（故障放行）与 0.1.0 行为一致；直接实例化 Redis 限流器的用户，
   异常语义从「返回 true」变为「向上抛出」。

---
