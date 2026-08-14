package org.example.apigovernancespringbootstarter.ratelimit;

/**
 * 限流器插件接口 —— 「一切皆插件」在限流维度的落地。
 *
 * <p>任何实现本接口的 Bean 都会被治理管道自动采用（优先级高于 yml 配置的默认限流器），
 * 从而实现「注册 Bean 配置」与「yml 配置」两种方式二选一。
 *
 * <h3>内置实现</h3>
 * <ul>
 *   <li>{@code local/TokenBucketRateLimiter}：本机令牌桶</li>
 *   <li>{@code local/SlidingWindowRateLimiter}：本机滑动窗口</li>
 *   <li>{@code redis/RedisTokenBucketRateLimiter}：Redis 令牌桶（封装 Redis + Lua）</li>
 *   <li>{@code redis/RedisSlidingWindowRateLimiter}：Redis 滑动窗口（封装 Redis + Lua）</li>
 * </ul>
 *
 * <h3>自定义插件（自定义算法策略）</h3>
 * <p>方式一：直接实现本接口并注册 {@code @Bean RateLimiter}，即可完全替换默认限流器；</p>
 * <pre>
 * &#64;Bean
 * public RateLimiter myRateLimiter() {
 *     return new RateLimiter() {
 *         public boolean tryAcquire(String key, int limit, int window) { ... }
 *         public String getName() { return "my-limiter"; }
 *     };
 * }
 * </pre>
 * <p>方式二：仅实现算法策略 {@link RateLimitStrategy} 并注册 {@code @Bean RateLimitStrategy}，
 * 配合 {@code api.governance.rate-limit.algorithm=custom} 使用。</p>
 *
 * @author API Governance Team
 * @since 1.0
 */
public interface RateLimiter {

    /**
     * 尝试获取一个许可（判断当前请求是否允许通过）。
     *
     * @param key          限流键（通常是 API 唯一标识）
     * @param limit        限流阈值（窗口内最大请求数）
     * @param windowSeconds 时间窗口（秒）
     * @return true 表示放行；false 表示被限流拒绝
     */
    boolean tryAcquire(String key, int limit, int windowSeconds);

    /**
     * 限流器名称（用于日志与管理接口展示）。
     *
     * @return 名称
     */
    String getName();

    /**
     * 获取指定 key 的当前计数（令牌数或窗口内请求数），用于监控。
     *
     * @param key 限流键
     * @return 当前计数；不支持时返回 -1
     */
    default long getCurrentCount(String key) {
        return -1;
    }

    /**
     * 重置指定 key 的限流状态。
     *
     * @param key 限流键
     */
    default void reset(String key) {
        // 默认空实现
    }

    /**
     * 重置全部限流状态（谨慎使用，可能影响性能）。
     */
    default void resetAll() {
        // 默认空实现
    }
}
