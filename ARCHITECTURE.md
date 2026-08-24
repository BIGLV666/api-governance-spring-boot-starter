# 架构设计与维护迭代指南

本文档面向**维护者与二次开发者**，说明整体架构、扩展点与迭代方法。

---

## 一、总体架构

```
                         ┌──────────────────────────────────────┐
                         │       Controller 治理主切面            │
                         │  GovernanceAspect                    │
                         │  @Around("within(@RestController *)")│
                         └──────────────────┬───────────────────┘
                                            │  默认拦截所有 Controller
                                            ▼
                              ┌─────────────────────────────┐
                              │  FilterContext（上下文载体）  │
                              │  apiKey / method / args /   │
                              │  rateLimit / logEnabled ... │
                              └──────────────┬──────────────┘
                                             │
                 ┌───────────────────────────┴───────────────────────────┐
                 ▼                                                       ▼
   ┌─────────────────────────┐                             ┌─────────────────────────┐
   │      前置过滤器链          │                             │      后置过滤器链         │
   │ ① 信息采集 (order 1)      │  任一返回 false 即短路         │ ① 记录耗时/更新统计(400)   │
   │ ② 流量统计 (order 100)    │                             │ ② 日志记录 (order 500)   │
   │ ③ 限流判断 (order 200)    │                             │ ③ 自定义...              │
   │ ④ 自定义...               │                             └─────────────────────────┘
   └────────────┬─────────────┘
                │ 全部通过
                ▼
        pjp.proceed()  ← 只调用一次
```

**设计要点**

1. **治理主切面唯一**：Controller 治理管道只有一个 `@Around`；异步动作使用仅匹配
   `@AsyncAction` 的独立精确切面，两者各自只调用一次 `proceed()`。
2. **管道过滤器**：所有治理逻辑都抽象为 `PreFilter` / `PostFilter` 插件，按 `order` 排序执行。
3. **短路语义**：前置链任一返回 `false` → 立即停止，不执行业务方法，由切面抛出 `GovernanceException`，
   经 `@RestControllerAdvice` 转为标准 JSON 拒绝响应。
4. **后置兜底**：后置链在 `finally` 中执行，业务成功/失败/拒绝都能得到一致的收尾处理。

---

## 二、包结构

```
io.github.biglv666.apigovernance
├── annotation        最小注解：RateLimit / Skip / NoLog
├── aspect            GovernanceAspect（Controller 治理主切面）
├── async             方法生命周期异步插件
│   ├── annotation    AsyncAction / AsyncHandler
│   ├── event         AsyncEvent / AsyncPhase / AsyncError
│   ├── spi           Executor / 异常 / 拒绝 / 事件增强扩展点
│   ├── aspect        精确注解切面
│   └── internal      启动注册、缓存、调度和默认实现
├── filter            管道接口：Filter / PreFilter / PostFilter / FilterContext / FilterChain
│   └── impl          内置过滤器
├── ratelimit         限流：RateLimiter / RateLimitStrategy / 枚举 / StrategyRateLimiter
│   ├── local         本机令牌桶 / 本机滑动窗口
│   └── redis         Redis 令牌桶 / Redis 滑动窗口（只封装）
├── metrics           内存指标：SlidingWindow / RequestRecord / ApiMetrics / MetricsRegistry
├── config            装配：ApiGovernanceProperties / ApiGovernanceAutoConfiguration
├── exception         拒绝异常：GovernanceException / GovernanceExceptionHandler
└── management        后台管理接口
```

---

## 三、配置装配（bean / yml 双配置）

`ApiGovernanceAutoConfiguration` 一次性注册默认全局配置与全部核心 Bean：

- 用户注册 `RateLimiter` Bean → 直接替换默认限流器（`@ConditionalOnMissingBean`）；
- 用户注册 `RateLimitStrategy` Bean + `algorithm=custom` → 包装为 `StrategyRateLimiter`；
- 其余全局默认值 → 通过 yml（`api.governance.*`）覆盖。

Redis 限流装配放在嵌套静态类 `RedisRateLimitConfiguration` 中，通过
`@ConditionalOnClass(name = "...StringRedisTemplate")` 隔离可选依赖，保证未引入 Redis 时也能正常加载。

---

## 四、限流体系（类型 × 算法 × 自定义）

| 维度 | 取值 | 说明 |
|------|------|------|
| 存储类型 | local / redis | 决定状态存放位置 |
| 算法 | token-bucket / sliding-window / custom | 决定限流策略 |
| 颗粒度 | 接口级（默认）/ 用户级 / 自定义 | 由 RateLimitKeyResolver 决定 |
| 自定义 | RateLimiter / RateLimitStrategy | 完全替换或仅替换算法 |

本机实现内存有界（最大键数上限 + LRU 淘汰）；Redis 实现仅封装 Lua 脚本，异常时降级放行。

---

## 五、内存指标与有界滑动窗口

`MetricsRegistry` 以 `ConcurrentHashMap<apiKey, ApiMetrics>` 组织指标。为避免内存膨胀：

- **单 API 最近记录**：`SlidingWindow<RequestRecord>` 双重边界（`window-size` 条数 + `window-seconds` 时间）；
- **全局 API 数量**：`max-apis` 上限，新增超限时按 LRU 淘汰最久未访问的 API。

指标随进程关闭自动销毁，无持久化，无后台清理线程（全部懒淘汰），保证轻量。

---

## 六、扩展点（如何添加插件）

### 1. 添加自定义前置/后置过滤器

实现 `PreFilter` / `PostFilter`，注册为 Spring Bean 即可（自动进入管道）。

```java
@Component
@Order(300)
public class MyPreFilter implements PreFilter {
    public boolean doFilter(FilterContext ctx) {
        ctx.setAttribute("myFlag", true);   // 通过上下文向后续过滤器传递数据
        return true;
    }
}
```

### 2. 添加自定义限流策略

- 完全替换：实现 `RateLimiter` 并注册 `@Bean`；
- 仅替换算法：实现 `RateLimitStrategy` 并注册 `@Bean`，配置 `algorithm=custom`。

### 3. 切换限流颗粒度（用户级等）

实现 `RateLimitKeyResolver` 并注册 `@Bean`，返回自定义限流键。默认实现为接口级
（`context.getApiKey()`）；例如用户级：`context.getApiKey() + "#user:" + userId`。
本机/Redis 限流均基于该键，因此无需改动限流器本身。

### 4. 添加管理接口

在 `GovernanceManagementController` 中新增端点即可，数据源统一来自
`MetricsRegistry` / `FilterChain` / `RateLimiter` / `ApiGovernanceProperties`。

### 5. 添加异步方法钩子

`@AsyncAction` 声明动作，`@AsyncHandler` 声明处理器。Registry 在所有单例创建后扫描、校验并缓存
处理方法；运行期按 `action + phase` 直接查询并按 order 提交。跨线程只传不可变 `AsyncEvent`，
调用参数、结果和原始异常只短暂暴露给调用线程上的 `AsyncEventEnricher`。

默认线程池、Handler 异常处理和任务拒绝处理均可通过 SPI Bean 替换。完整契约见
`ASYNC_ACTIONS.md`，内部代码流程见 `ASYNC_IMPLEMENTATION.md`。

---

## 七、维护迭代方法

### 新增一个治理能力（例如参数校验）

1. 新建 `XxxFilter implements PreFilter`，选择合理的 `order`（参数校验建议 300~399）；
2. 在 `ApiGovernanceAutoConfiguration` 中注册为 `@Bean`（或直接 `@Component`）；
3. 如需配置项，在 `ApiGovernanceProperties` 增加对应字段；
4. 在 README / 本文档更新说明。

### 修改拒绝响应格式

1. 修改 `GovernanceExceptionHandler#buildBody`；
2. 保持字段向后兼容（只增不改），避免破坏既有管理工具。

### 保证向后兼容

- 注解属性仅新增、保留默认值，不删除既有属性；
- 配置前缀 `api.governance.*` 保持稳定；
- 管理接口返回结构保持稳定（新增字段优于修改字段）。

### 测试方法

- 自动装配测试：`ApplicationContextRunner` 验证 bean/yml 两条装配路径；
- 单元测试：验证限流算法与有界滑动窗口的边界行为；
- 改动后运行 `./mvnw clean test`。

---

## 八、性能与内存考量

| 关注点 | 策略 |
|--------|------|
| 切面开销 | Controller 主切面按类匹配；异步切面仅匹配显式 `@AsyncAction` 方法 |
| 限流 | 本机 O(1)（令牌桶）/ O(窗口内请求数)（滑动窗口），无锁或细粒度锁 |
| 指标 | `AtomicLong` 无锁计数；有界滑动窗口懒淘汰，无后台线程 |
| 日志 | 入参/响应体默认关闭，字符串截断防膨胀 |
| 异步任务 | 启动期扫描缓存；独立有界线程池；事件不持有原始调用对象 |
| 内存 | 全部有上界（指标条数、API 数量、限流键数量），随进程关闭释放 |
```
