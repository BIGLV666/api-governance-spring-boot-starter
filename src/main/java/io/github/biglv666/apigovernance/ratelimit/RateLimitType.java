package io.github.biglv666.apigovernance.ratelimit;

/**
 * 限流器存储类型枚举。
 *
 * <p>用于 yml 配置 {@code api.governance.rate-limit.type}，决定限流状态存放在哪里：
 * <ul>
 *   <li>{@link #LOCAL}：本机内存，单机限流，零外部依赖；</li>
 *   <li>{@link #REDIS}：Redis 中心存储，分布式限流，多实例共享。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public enum RateLimitType {

    /** 本机限流（内存）。 */
    LOCAL("local", "本机限流"),

    /** Redis 限流（分布式）。 */
    REDIS("redis", "Redis 限流");

    private final String code;
    private final String description;

    RateLimitType(String code, String description) {
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
     * 根据配置代码解析枚举，未知值回退到 {@link #LOCAL}。
     *
     * @param code 配置代码
     * @return 对应枚举
     */
    public static RateLimitType fromCode(String code) {
        for (RateLimitType type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return LOCAL;
    }
}
