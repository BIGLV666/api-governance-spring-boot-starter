package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.alert.internal.AlertDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限流器故障降级装饰器 —— 统一处理限流器（主要是 Redis 分布式限流）执行异常。
 *
 * <h3>降级策略（{@code api.governance.rate-limit.fail-strategy}）</h3>
 * <ul>
 *   <li>{@code open}（默认）：故障时<b>放行</b>，可用性优先 —— 限流组件的故障不拖垮业务，
 *       与 0.1.0 的内置行为一致；</li>
 *   <li>{@code close}：故障时<b>拒绝</b>，配额优先 —— 抛出
 *       {@link RateLimiterUnavailableException}，由 {@code RateLimitFilter} 转换为 503 拒绝，
 *       与普通 429 限流拒绝区分。</li>
 * </ul>
 *
 * <p>无论哪种策略，故障都会产生 {@code RATE_LIMITER_FAILURE} 告警事件（若已配置告警通知器）
 * 并记录 error 日志。策略仅对 {@link #tryAcquire} 生效；管理接口的查询/重置操作
 * 异常由管理控制器自行捕获展示。
 *
 * <p>自动配置中 Redis 限流器 Bean 均由本类包装后暴露；用户自定义
 * {@code RateLimiter} Bean 不做包装（用户对自己实现的异常语义负责）。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class FailSafeRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(FailSafeRateLimiter.class);

    /** 被包装的真实限流器。 */
    private final RateLimiter delegate;

    /** 是否为 fail-close（故障拒绝）模式。 */
    private final boolean failClose;

    /** 告警分发器（可能为 null：告警关闭时）。 */
    private final AlertDispatcher alertDispatcher;

    /**
     * 构造故障降级装饰器。
     *
     * @param delegate        被包装的真实限流器
     * @param failClose       true = 故障拒绝（close），false = 故障放行（open）
     * @param alertDispatcher 告警分发器（可为 null）
     */
    public FailSafeRateLimiter(RateLimiter delegate, boolean failClose, AlertDispatcher alertDispatcher) {
        this.delegate = delegate;
        this.failClose = failClose;
        this.alertDispatcher = alertDispatcher;
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        try {
            return delegate.tryAcquire(key, limit, windowSeconds);
        } catch (Exception e) {
            log.error("限流器执行故障 - limiter: {}, key: {}, 策略: {}, 错误: {}",
                    delegate.getName(), key, failClose ? "close(拒绝)" : "open(放行)", e.getMessage(), e);
            if (alertDispatcher != null) {
                // 摘要只取异常 message，不把堆栈带入告警渠道
                alertDispatcher.publishRateLimiterFailure(delegate.getName(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            }
            if (failClose) {
                throw new RateLimiterUnavailableException(
                        "限流器暂不可用（fail-strategy=close）: " + delegate.getName(), e);
            }
            return true;
        }
    }

    @Override
    public String getName() {
        return delegate.getName();
    }

    @Override
    public long getCurrentCount(String key) {
        return delegate.getCurrentCount(key);
    }

    @Override
    public void reset(String key) {
        delegate.reset(key);
    }

    @Override
    public void resetAll() {
        delegate.resetAll();
    }
}
