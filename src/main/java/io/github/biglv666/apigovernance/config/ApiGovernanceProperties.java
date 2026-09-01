package io.github.biglv666.apigovernance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * API 治理全局配置属性（前缀 {@code api.governance}）。
 *
 * <p>「在项目注册一次默认全局配置」的落地：本类在自动配置中通过
 * {@code @EnableConfigurationProperties} 一次性注册为 Bean，所有全局默认值在此集中定义，
 * 既可通过 yml 覆盖，也可通过注册自定义 Bean 覆盖。
 *
 * <h3>配置示例（application.yml）</h3>
 * <pre>
 * api:
 *   governance:
 *     enabled: true                 # 总开关
 *     log:
 *       enabled: true               # 日志总开关
 *       slow-threshold-ms: 1000     # 慢方法阈值
 *     rate-limit:
 *       type: local                 # local / redis
 *       algorithm: token-bucket     # token-bucket / sliding-window / custom
 *       default-limit: 100          # 全局默认限流阈值（-1 表示不限制）
 *       default-window: 1           # 全局默认窗口（秒）
 *       fail-strategy: open         # 限流器故障降级：open=放行 / close=503 拒绝（作用于 Redis）
 *     metrics:
 *       window-size: 100            # 每个 API 保留的最近记录条数
 *       window-seconds: 300         # 记录保留时长（秒）
 *       max-apis: 1000              # 最大统计的 API 数量
 *       micrometer-enabled: true    # 指标桥接到 Micrometer
 *     alert:
 *       enabled: true               # 告警总开关
 *       suppress-interval-ms: 10000 # 告警抑制窗口（毫秒）
 *       webhook:
 *         enabled: false            # 内置 Webhook 通知器
 *         url: ""                   # webhook 目标地址
 *     management:
 *       enabled: true               # 管理接口开关
 *       auth-token: ""              # 非空时启用管理接口令牌鉴权
 *     async:
 *       enabled: true               # 方法生命周期异步钩子开关
 *       core-pool-size: 2           # 独立线程池核心线程数
 *       max-pool-size: 8            # 独立线程池最大线程数
 *       queue-capacity: 1000         # 有界队列容量
 *     tracing:
 *       enabled: true                # OpenTelemetry 链路追踪总开关
 *       kafka: true                  # 自动启用 Kafka Observation
 *       rabbit: true                 # 自动启用 RabbitMQ Observation
 * </pre>
 *
 * @author API Governance Team
 * @since 1.0
 */
@ConfigurationProperties(prefix = "api.governance")
public class ApiGovernanceProperties {

    /** 治理总开关。 */
    private boolean enabled = true;

    /** 日志配置。 */
    private Log log = new Log();

    /** 限流配置。 */
    private RateLimit rateLimit = new RateLimit();

    /** 指标统计配置。 */
    private Metrics metrics = new Metrics();

    /** 管理接口配置。 */
    private Management management = new Management();

    /** 方法生命周期异步钩子配置。 */
    private Async async = new Async();

    /** 告警配置。 */
    private Alert alert = new Alert();

    /** 分布式链路追踪配置。 */
    private Tracing tracing = new Tracing();

    /** 内置过滤器开关配置。 */
    private Filters filters = new Filters();

    /**
     * 治理范围包前缀（0.4.0 新增）：非空时<b>仅治理</b>命中这些前缀的包，
     * 其余包中的 Controller 完全放行（与 @Skip 等价语义）。空列表 = 治理全部。
     */
    private List<String> includePackages = new ArrayList<>();

    /**
     * 排除包前缀（0.4.0 新增）：命中前缀的包不参与治理，优先级高于 {@link #includePackages}。
     * 常用于排除健康检查、内部调试控制器等。
     */
    private List<String> excludePackages = new ArrayList<>();

    // ==================== getters / setters ====================

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Log getLog() {
        return log;
    }

    public void setLog(Log log) {
        this.log = log;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(RateLimit rateLimit) {
        this.rateLimit = rateLimit;
    }

    public Metrics getMetrics() {
        return metrics;
    }

    public void setMetrics(Metrics metrics) {
        this.metrics = metrics;
    }

    public Management getManagement() {
        return management;
    }

    public void setManagement(Management management) {
        this.management = management;
    }

    public Async getAsync() {
        return async;
    }

    public void setAsync(Async async) {
        this.async = async;
    }

    public Alert getAlert() {
        return alert;
    }

    public void setAlert(Alert alert) {
        this.alert = alert;
    }

    public Tracing getTracing() {
        return tracing;
    }

    public void setTracing(Tracing tracing) {
        this.tracing = tracing;
    }

    public Filters getFilters() {
        return filters;
    }

    public void setFilters(Filters filters) {
        this.filters = filters;
    }

    public List<String> getIncludePackages() {
        return includePackages;
    }

    public void setIncludePackages(List<String> includePackages) {
        this.includePackages = includePackages == null ? new ArrayList<>() : includePackages;
    }

    public List<String> getExcludePackages() {
        return excludePackages;
    }

    public void setExcludePackages(List<String> excludePackages) {
        this.excludePackages = excludePackages == null ? new ArrayList<>() : excludePackages;
    }

    // ==================== 嵌套配置类 ====================

    /**
     * 日志配置。
     */
    public static class Log {

        /** 日志总开关（默认开启）。 */
        private boolean enabled = true;

        /** 是否输出请求入参（默认关闭，避免敏感信息与超大日志）。 */
        private boolean logRequestParams = false;

        /** 是否输出响应体（默认关闭）。 */
        private boolean logResponse = false;

        /** 慢方法阈值（毫秒），超过即记录慢方法并告警。 */
        private long slowThresholdMs = 1000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isLogRequestParams() {
            return logRequestParams;
        }

        public void setLogRequestParams(boolean logRequestParams) {
            this.logRequestParams = logRequestParams;
        }

        public boolean isLogResponse() {
            return logResponse;
        }

        public void setLogResponse(boolean logResponse) {
            this.logResponse = logResponse;
        }

        public long getSlowThresholdMs() {
            return slowThresholdMs;
        }

        public void setSlowThresholdMs(long slowThresholdMs) {
            this.slowThresholdMs = slowThresholdMs;
        }
    }

    /**
     * 限流配置。
     */
    public static class RateLimit {

        /** 限流器类型：local（本机）/ redis（分布式）。 */
        private String type = "local";

        /** 限流算法：token-bucket / sliding-window / custom。 */
        private String algorithm = "token-bucket";

        /** 全局默认限流阈值（-1 表示不限制）。 */
        private int defaultLimit = -1;

        /** 全局默认时间窗口（秒）。 */
        private int defaultWindow = 1;

        /** 限流拒绝时的 HTTP 状态码。 */
        private int statusCode = 429;

        /** 限流拒绝提示语。 */
        private String message = "请求过于频繁，请稍后重试";

        /**
         * 限流器故障降级策略（当前作用于 Redis 分布式限流）：
         * {@code open} = 故障时放行（默认，可用性优先）；{@code close} = 故障时拒绝（503，配额优先）。
         */
        private String failStrategy = "open";

        /**
         * 本机限流器的最大键数量上限（超出后淘汰已过期的键，不足再淘汰最久未使用的键）。
         * SpEL 参数维度限流（按 userId 等）会产生高基数键，内存敏感场景可调小。
         */
        private int maxEntries = 10_000;

        /**
         * 获取限流器故障降级策略（归一化为小写，非法值回退 open）。
         *
         * @return "open" 或 "close"
         */
        public String getFailStrategy() {
            return failStrategy == null ? "open" : failStrategy.trim().toLowerCase();
        }

        public void setFailStrategy(String failStrategy) {
            this.failStrategy = failStrategy;
        }

        public int getMaxEntries() {
            return maxEntries;
        }

        public void setMaxEntries(int maxEntries) {
            this.maxEntries = maxEntries;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getAlgorithm() {
            return algorithm;
        }

        public void setAlgorithm(String algorithm) {
            this.algorithm = algorithm;
        }

        public int getDefaultLimit() {
            return defaultLimit;
        }

        public void setDefaultLimit(int defaultLimit) {
            this.defaultLimit = defaultLimit;
        }

        public int getDefaultWindow() {
            return defaultWindow;
        }

        public void setDefaultWindow(int defaultWindow) {
            this.defaultWindow = defaultWindow;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public void setStatusCode(int statusCode) {
            this.statusCode = statusCode;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

    /**
     * 内存指标统计配置。
     */
    public static class Metrics {

        /** 每个 API 保留的最近请求记录条数（有界滑动窗口的硬性上限）。 */
        private int windowSize = 100;

        /** 每个 API 最近记录的时间窗口（秒），超过时长的记录被淘汰。 */
        private int windowSeconds = 300;

        /** 最大统计的 API 数量（超限按 LRU 淘汰）。 */
        private int maxApis = 1000;

        /** 是否将治理指标桥接到 Micrometer（存在 MeterRegistry Bean 时生效，默认开启）。 */
        private boolean micrometerEnabled = true;

        public boolean isMicrometerEnabled() {
            return micrometerEnabled;
        }

        public void setMicrometerEnabled(boolean micrometerEnabled) {
            this.micrometerEnabled = micrometerEnabled;
        }

        public int getWindowSize() {
            return windowSize;
        }

        public void setWindowSize(int windowSize) {
            this.windowSize = windowSize;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        public void setWindowSeconds(int windowSeconds) {
            this.windowSeconds = windowSeconds;
        }

        public int getMaxApis() {
            return maxApis;
        }

        public void setMaxApis(int maxApis) {
            this.maxApis = maxApis;
        }
    }

    /**
     * 后台管理接口配置。
     */
    public static class Management {

        /** 管理接口开关。 */
        private boolean enabled = true;

        /** 管理接口基础路径。 */
        private String basePath = "/api-governance";

        /**
         * 管理接口鉴权令牌。非空时启用鉴权：请求必须携带 {@link #authHeader} 指定的
         * header 且值与令牌一致，否则返回 401。为空（默认）表示不启用鉴权。
         * <p>生产环境建议通过环境变量注入，如 {@code auth-token: ${GOVERNANCE_TOKEN}}。
         */
        private String authToken = "";

        /** 管理接口鉴权令牌的请求头名称（仅 {@link #authToken} 非空时生效）。 */
        private String authHeader = "X-Governance-Token";

        /**
         * 管理端点写操作开关（0.4.0 新增）：控制 {@code POST /rate-limiter/reset*}、
         * {@code DELETE /metrics*} 等变更类端点是否可用。关闭时写端点返回失败提示，只读端点不受影响。
         * 默认开启，保持 0.3.0 行为。
         */
        private boolean mutationsEnabled = true;

        public boolean isMutationsEnabled() {
            return mutationsEnabled;
        }

        public void setMutationsEnabled(boolean mutationsEnabled) {
            this.mutationsEnabled = mutationsEnabled;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        /** 判断管理接口鉴权是否启用（令牌非空白即启用）。 */
        public boolean isAuthEnabled() {
            return authToken != null && !authToken.trim().isEmpty();
        }

        public String getAuthHeader() {
            return authHeader;
        }

        public void setAuthHeader(String authHeader) {
            this.authHeader = authHeader;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getBasePath() {
            return basePath;
        }

        public void setBasePath(String basePath) {
            this.basePath = basePath;
        }
    }

    /**
     * 告警配置（慢方法 / 限流拒绝 / 限流器故障三类事件）。
     */
    public static class Alert {

        /** 告警总开关（关闭时不创建告警分发器，已注册的通知器不会收到事件）。 */
        private boolean enabled = true;

        /**
         * 告警抑制窗口（毫秒）：同一 {@code (告警类型, apiKey)} 在窗口内只分发一次，
         * 防止告警风暴。0 表示不抑制（不推荐，慢接口会被每个慢请求触发一次）。
         */
        private long suppressIntervalMs = 10000L;

        /** 内置 Webhook 通知器配置。 */
        private Webhook webhook = new Webhook();

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getSuppressIntervalMs() {
            return suppressIntervalMs;
        }

        public void setSuppressIntervalMs(long suppressIntervalMs) {
            this.suppressIntervalMs = suppressIntervalMs;
        }

        public Webhook getWebhook() {
            return webhook;
        }

        public void setWebhook(Webhook webhook) {
            this.webhook = webhook;
        }
    }

    /**
     * 内置 Webhook 告警通知器配置（基于 JDK HttpClient，零额外依赖）。
     */
    public static class Webhook {

        /** 是否启用 Webhook 通知器。 */
        private boolean enabled = false;

        /** Webhook 目标地址（POST JSON）。 */
        private String url = "";

        /** 单次请求超时（毫秒）。 */
        private long timeoutMs = 3000L;

        /**
         * 可选的鉴权令牌：非空时以 {@code X-Governance-Token} 请求头携带，
         * 供接收端校验来源。请勿把令牌写入日志。
         */
        private String secretToken = "";

        /**
         * 通知平台格式：{@code generic}（默认，框架自有 JSON 格式）/
         * {@code dingtalk}（钉钉机器人）/ {@code wecom}（企业微信机器人）/
         * {@code feishu}（飞书机器人）。非 generic 平台按对应机器人的原生消息格式构造请求体。
         */
        private String platform = "generic";

        /**
         * 加签密钥：仅 {@code platform=dingtalk} 时生效。非空时按钉钉「加签」安全设置，
         * 在 URL 上追加 {@code &timestamp=...&sign=...}（HMAC-SHA256 + Base64）。
         * 建议通过环境变量注入，如 {@code sign-secret: ${DINGTALK_SECRET}}。
         */
        private String signSecret = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public String getSecretToken() {
            return secretToken;
        }

        public void setSecretToken(String secretToken) {
            this.secretToken = secretToken;
        }

        public String getPlatform() {
            return platform;
        }

        public void setPlatform(String platform) {
            this.platform = platform;
        }

        public String getSignSecret() {
            return signSecret;
        }

        public void setSignSecret(String signSecret) {
            this.signSecret = signSecret;
        }
    }

    /**
     * 内置过滤器开关：按过滤器粒度控制装配。关闭后对应过滤器 Bean 不再创建；
     * 注册同名类型的自定义 Bean 也可替换内置实现（内置 Bean 带 {@code ConditionalOnMissingBean}）。
     *
     * <p>注意 {@code logging} 与 {@code log.enabled} 的区别：前者是「不装配日志过滤器」，
     * 后者是「装配但全局不输出日志」（指标统计仍保留）。
     */
    public static class Filters {

        /** 信息采集过滤器（元数据采集，order=1）。 */
        private boolean metadataCollector = true;

        /** 流量统计过滤器（order=100）。 */
        private boolean trafficStatistics = true;

        /** 限流判断过滤器（order=200）。 */
        private boolean rateLimit = true;

        /** 慢方法/耗时统计过滤器（order=400）。 */
        private boolean slowMethod = true;

        /** 日志记录过滤器（order=500）。 */
        private boolean logging = true;

        public boolean isMetadataCollector() {
            return metadataCollector;
        }

        public void setMetadataCollector(boolean metadataCollector) {
            this.metadataCollector = metadataCollector;
        }

        public boolean isTrafficStatistics() {
            return trafficStatistics;
        }

        public void setTrafficStatistics(boolean trafficStatistics) {
            this.trafficStatistics = trafficStatistics;
        }

        public boolean isRateLimit() {
            return rateLimit;
        }

        public void setRateLimit(boolean rateLimit) {
            this.rateLimit = rateLimit;
        }

        public boolean isSlowMethod() {
            return slowMethod;
        }

        public void setSlowMethod(boolean slowMethod) {
            this.slowMethod = slowMethod;
        }

        public boolean isLogging() {
            return logging;
        }

        public void setLogging(boolean logging) {
            this.logging = logging;
        }
    }

    /**
     * Method lifecycle asynchronous hook configuration.
     */
    public static class Async {

        /** Whether annotation-driven asynchronous actions are enabled. */
        private boolean enabled = true;

        /** Core size of the isolated framework thread pool. */
        private int corePoolSize = 2;

        /** Maximum size of the isolated framework thread pool. */
        private int maxPoolSize = 8;

        /** Capacity of the bounded work queue. */
        private int queueCapacity = 1000;

        /** Idle timeout for non-core threads, in seconds. */
        private int keepAliveSeconds = 60;

        /** Worker thread name prefix. */
        private String threadNamePrefix = "api-governance-async-";

        /** Maximum shutdown wait for queued work, in seconds. */
        private int awaitTerminationSeconds = 5;

        /**
         * 启动期交叉校验放行开关（0.5.0 新增）：默认 {@code false}（fail-fast）——
         * {@code @AsyncHandler} 引用不存在的 {@code @AsyncAction} 时启动失败；
         * 设为 {@code true} 仅记 warn 日志（兼容先写 handler 后补 action 的场景）。
         */
        private boolean ignoreUnmatchedHandlers = false;

        /**
         * 内置 HTTP 上下文 enricher 开关（0.5.0 新增）：开启后异步事件的 {@code data}
         * 会携带当前请求的 {@code requestUri} / {@code httpMethod} / {@code clientIp} 快照。
         */
        private boolean webContextEnrichment = true;

        public boolean isIgnoreUnmatchedHandlers() {
            return ignoreUnmatchedHandlers;
        }

        public void setIgnoreUnmatchedHandlers(boolean ignoreUnmatchedHandlers) {
            this.ignoreUnmatchedHandlers = ignoreUnmatchedHandlers;
        }

        public boolean isWebContextEnrichment() {
            return webContextEnrichment;
        }

        public void setWebContextEnrichment(boolean webContextEnrichment) {
            this.webContextEnrichment = webContextEnrichment;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public int getKeepAliveSeconds() {
            return keepAliveSeconds;
        }

        public void setKeepAliveSeconds(int keepAliveSeconds) {
            this.keepAliveSeconds = keepAliveSeconds;
        }

        public String getThreadNamePrefix() {
            return threadNamePrefix;
        }

        public void setThreadNamePrefix(String threadNamePrefix) {
            this.threadNamePrefix = threadNamePrefix;
        }

        public int getAwaitTerminationSeconds() {
            return awaitTerminationSeconds;
        }

        public void setAwaitTerminationSeconds(int awaitTerminationSeconds) {
            this.awaitTerminationSeconds = awaitTerminationSeconds;
        }
    }

    /**
     * Micrometer Tracing integration. Messaging integrations activate only
     * when the corresponding client library is present in the application.
     */
    public static class Tracing {

        /** Whether automatic trace integration is enabled. */
        private boolean enabled = true;

        /** Whether framework asynchronous tasks inherit the submitting trace. */
        private boolean asyncContextPropagation = true;

        /** Whether Spring Kafka templates and listener containers enable Observation. */
        private boolean kafka = true;

        /** Whether Spring AMQP templates and listener containers enable Observation. */
        private boolean rabbit = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public boolean isAsyncContextPropagation() {
            return asyncContextPropagation;
        }

        public void setAsyncContextPropagation(boolean asyncContextPropagation) {
            this.asyncContextPropagation = asyncContextPropagation;
        }

        public boolean isKafka() {
            return kafka;
        }

        public void setKafka(boolean kafka) {
            this.kafka = kafka;
        }

        public boolean isRabbit() {
            return rabbit;
        }

        public void setRabbit(boolean rabbit) {
            this.rabbit = rabbit;
        }
    }
}
