package io.github.biglv666.apigovernance.ratelimit.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 本机滑动窗口限流器。
 *
 * <h3>算法原理</h3>
 * <ol>
 *   <li>每个限流键维护一个「请求时间戳」队列；</li>
 *   <li>新请求到来时，先清除时间窗口（{@code now - window}）之外的旧时间戳；</li>
 *   <li>若窗口内时间戳数量小于 {@code limit}，则放行并记录当前时间戳，否则拒绝。</li>
 * </ol>
 *
 * <h3>特点</h3>
 * <ul>
 *   <li>精确限流：严格约束时间窗口内的请求数；</li>
 *   <li>单键内存占用 = 窗口内请求数（有自然上界 {@code limit}）；</li>
 *   <li>键数量上限可配置（{@code api.governance.rate-limit.max-entries}），
 *       超限淘汰策略见 {@link AbstractBoundedLocalRateLimiter}。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class SlidingWindowRateLimiter extends AbstractBoundedLocalRateLimiter<SlidingWindowRateLimiter.SlidingWindow> {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    /** 使用默认键数量上限构造。 */
    public SlidingWindowRateLimiter() {
        super(DEFAULT_MAX_ENTRIES);
    }

    /**
     * 构造并指定最大键数量上限。
     *
     * @param maxEntries 最大限流键数量
     */
    public SlidingWindowRateLimiter(int maxEntries) {
        super(maxEntries);
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        return acquireState(key, limit, windowSeconds).tryAcquire();
    }

    @Override
    public String getName() {
        return "sliding-window-local";
    }

    @Override
    public long getCurrentCount(String key) {
        SlidingWindow window = getCurrentState(key);
        return window == null ? 0 : window.getCount();
    }

    @Override
    public void reset(String key) {
        SlidingWindow window = getCurrentState(key);
        if (window != null) {
            window.reset();
            log.debug("重置滑动窗口 - key: {}", key);
        }
    }

    @Override
    public void resetAll() {
        clearStates();
        log.info("清空所有滑动窗口");
    }

    /** 当前窗口数量（供管理接口展示）。 */
    public int getWindowCount() {
        return getStateCount();
    }

    @Override
    protected boolean isExpired(SlidingWindow state, long now) {
        // 窗口已滑出：全部时间戳失效，删除后重建状态与原状态限流语义等价
        return now - state.lastAccess >= state.windowMillis;
    }

    @Override
    protected long accessTime(SlidingWindow state) {
        return state.lastAccess;
    }

    @Override
    protected boolean matches(SlidingWindow state, int limit, int windowSeconds) {
        return state.limit == limit && state.windowSeconds == windowSeconds;
    }

    @Override
    protected SlidingWindow newState(int limit, int windowSeconds) {
        return new SlidingWindow(limit, windowSeconds);
    }

    @Override
    protected void touch(SlidingWindow state) {
        state.touch();
    }

    @Override
    protected void reset(SlidingWindow state) {
        state.reset();
    }

    /**
     * 单个限流键的滑动窗口状态。
     */
    static final class SlidingWindow {

        /** 限流阈值。 */
        final int limit;

        /** 时间窗口（秒）。 */
        final int windowSeconds;

        /** 窗口长度（毫秒）。 */
        final long windowMillis;

        /** 窗口内请求时间戳队列。 */
        private final ConcurrentLinkedDeque<Long> timestamps = new ConcurrentLinkedDeque<>();

        /** 最近访问时间戳（用于过期判断与 LRU 淘汰）。 */
        private volatile long lastAccess;

        SlidingWindow(int limit, int windowSeconds) {
            this.limit = limit;
            this.windowSeconds = Math.max(1, windowSeconds);
            this.windowMillis = this.windowSeconds * 1000L;
            this.lastAccess = System.currentTimeMillis();
        }

        void touch() {
            lastAccess = System.currentTimeMillis();
        }

        /**
         * 尝试获取许可。
         *
         * @return true 放行，false 拒绝
         */
        synchronized boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long windowStart = now - windowMillis;
            // 清除窗口外的旧记录
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() < limit) {
                timestamps.addLast(now);
                return true;
            }
            return false;
        }

        synchronized int getCount() {
            long now = System.currentTimeMillis();
            long windowStart = now - windowMillis;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            return timestamps.size();
        }

        synchronized void reset() {
            timestamps.clear();
        }
    }
}
