package org.example.apigovernancespringbootstarter.config;

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
}
