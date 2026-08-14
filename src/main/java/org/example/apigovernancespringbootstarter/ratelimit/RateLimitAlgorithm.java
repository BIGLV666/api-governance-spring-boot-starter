package org.example.apigovernancespringbootstarter.ratelimit;

/**
 * 限流算法枚举。
 *
 * <p>用于 yml 配置 {@code api.governance.rate-limit.algorithm}：
 * <ul>
 *   <li>{@link #TOKEN_BUCKET}：令牌桶，平滑限流、支持突发流量；</li>
 *   <li>{@link #SLIDING_WINDOW}：滑动窗口，精确限流；</li>
 *   <li>{@link #CUSTOM}：自定义算法策略，配合 {@link RateLimitStrategy} Bean 使用。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public enum RateLimitAlgorithm {

    /** 令牌桶算法。 */
    TOKEN_BUCKET("token-bucket", "令牌桶算法"),

    /** 滑动窗口算法。 */
    SLIDING_WINDOW("sliding-window", "滑动窗口算法"),

    /** 自定义算法策略。 */
    CUSTOM("custom", "自定义算法策略");

    private final String code;
    private final String description;

    RateLimitAlgorithm(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public String getDescription() {
        return description;
    }

    /**
     * 根据配置代码解析枚举，未知值回退到 {@link #TOKEN_BUCKET}。
     *
     * @param code 配置代码
     * @return 对应枚举
     */
    public static RateLimitAlgorithm fromCode(String code) {
        for (RateLimitAlgorithm algorithm : values()) {
            if (algorithm.code.equalsIgnoreCase(code)) {
                return algorithm;
            }
        }
        return TOKEN_BUCKET;
    }
}
