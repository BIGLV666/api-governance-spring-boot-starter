package io.github.biglv666.apigovernance.ratelimit;

/**
 * 限流器不可用异常 —— 限流器（如 Redis 分布式限流）执行故障时由
 * {@code FailSafeRateLimiter} 在 {@code fail-strategy=close} 模式下抛出。
 *
 * <p>{@code RateLimitFilter} 捕获本异常后将请求按「限流服务暂不可用」处理：
 * 记录拒绝指标、以 503 状态码短路，与普通的 429 限流拒绝区分开，便于运维告警定位。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class RateLimiterUnavailableException extends RuntimeException {

    /**
     * 构造异常。
     *
     * @param message 故障描述（不含敏感信息）
     * @param cause   原始异常
     */
    public RateLimiterUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
