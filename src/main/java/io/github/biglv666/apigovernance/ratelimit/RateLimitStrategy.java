package io.github.biglv666.apigovernance.ratelimit;

/**
 * 限流算法策略接口 —— 自定义算法的最小实现契约。
 *
 * <p>若你只想自定义「算法」（令牌桶、滑动窗口之外的任意算法），而不关心存储方式，
 * 实现本接口即可。它只有一个抽象方法，因此也支持 Lambda 表达式。
 *
 * <h3>使用步骤</h3>
 * <ol>
 *   <li>实现本接口（自行在实现类内维护 per-key 状态）；</li>
 *   <li>注册为 {@code @Bean RateLimitStrategy}；</li>
 *   <li>配置 {@code api.governance.rate-limit.algorithm=custom}。</li>
 * </ol>
 *
 * <h3>示例：固定窗口计数器</h3>
 * <pre>
 * &#64;Bean
 * public RateLimitStrategy fixedWindowStrategy() {
 *     // 每次调用都会传入 key/limit/window，实现类自行按 key 维护计数
 *     return new FixedWindowStrategy();
 * }
 * </pre>
 *
 * <p>说明：自定义策略由 {@link StrategyRateLimiter} 适配成完整的 {@link RateLimiter}，
 * 因此无需重复实现管理方法（获取计数/重置等），这些方法默认返回安全值，可按需覆写。
 *
 * @author API Governance Team
 * @since 1.0
 */
@FunctionalInterface
public interface RateLimitStrategy {

    /**
     * 尝试获取一个许可（执行自定义算法判断）。
     *
     * @param key          限流键
     * @param limit        限流阈值
     * @param windowSeconds 时间窗口（秒）
     * @return true 放行；false 拒绝
     */
    boolean tryAcquire(String key, int limit, int windowSeconds);
}
