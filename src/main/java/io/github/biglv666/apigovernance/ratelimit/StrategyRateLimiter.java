package io.github.biglv666.apigovernance.ratelimit;

/**
 * 策略适配限流器 —— 将「算法策略」{@link RateLimitStrategy} 适配为「限流器插件」
 * {@link RateLimiter}。
 *
 * <p>职责非常单一：把治理管道对 {@link RateLimiter} 的调用，转发给用户提供的
 * {@link RateLimitStrategy}，从而让「自定义算法策略」与「内置限流器」共享同一条调用链。
 *
 * <p>管理方法（{@code getCurrentCount}/{@code reset}/{@code resetAll}）默认不感知策略内部状态，
 * 返回安全默认值；如需支持监控/重置，可在自定义策略中自行维护状态并在适配处扩展。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class StrategyRateLimiter implements RateLimiter {

    /** 限流器对外名称。 */
    private final String name;

    /** 被适配的算法策略。 */
    private final RateLimitStrategy strategy;

    /**
     * 构造策略适配限流器。
     *
     * @param name     限流器名称（通常取自策略类名或配置）
     * @param strategy 算法策略
     */
    public StrategyRateLimiter(String name, RateLimitStrategy strategy) {
        if (strategy == null) {
            throw new IllegalArgumentException("RateLimitStrategy 不能为 null");
        }
        this.name = name;
        this.strategy = strategy;
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        return strategy.tryAcquire(key, limit, windowSeconds);
    }

    @Override
    public String getName() {
        return name;
    }
}
