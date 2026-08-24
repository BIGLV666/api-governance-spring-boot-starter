package io.github.biglv666.apigovernance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 *     metrics:
 *       window-size: 100            # 每个 API 保留的最近记录条数
 *       window-seconds: 300         # 记录保留时长（秒）
 *       max-apis: 1000              # 最大统计 API 数量
 *     management:
 *       enabled: true               # 管理接口开关
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

    /** 分布式链路追踪配置。 */
    private Tracing tracing = new Tracing();

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

    public Tracing getTracing() {
        return tracing;
    }

    public void setTracing(Tracing tracing) {
        this.tracing = tracing;
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
