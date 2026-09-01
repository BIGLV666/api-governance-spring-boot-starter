package io.github.biglv666.apigovernance.ratelimit.local;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 本机令牌桶限流器。
 *
 * <h3>算法原理</h3>
 * <ol>
 *   <li>每个限流键维护一个容量为 {@code limit} 的令牌桶；</li>
 *   <li>令牌以 {@code limit / windowSeconds}（个/秒）的恒定速率补充；</li>
 *   <li>请求到来时尝试扣减 1 个令牌：成功则放行，失败则拒绝；</li>
 *   <li>桶满时多余令牌丢弃，从而天然支持<b>突发流量</b>。</li>
 * </ol>
 *
 * <h3>特点</h3>
 * <ul>
 *   <li>平滑限流、支持短时突发；</li>
 *   <li>基于时间计算，仅需 O(1) 状态，性能好；</li>
 *   <li>桶数量上限可配置（{@code api.governance.rate-limit.max-entries}），
 *       超限淘汰策略见 {@link AbstractBoundedLocalRateLimiter}。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class TokenBucketRateLimiter extends AbstractBoundedLocalRateLimiter<TokenBucketRateLimiter.TokenBucket> {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /** 使用默认键数量上限构造。 */
    public TokenBucketRateLimiter() {
        super(DEFAULT_MAX_ENTRIES);
    }

    /**
     * 构造并指定最大键数量上限。
     *
     * @param maxEntries 最大限流键数量
     */
    public TokenBucketRateLimiter(int maxEntries) {
        super(maxEntries);
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        return acquireState(key, limit, windowSeconds).tryAcquire();
    }

    @Override
    public String getName() {
        return "token-bucket-local";
    }

    @Override
    public long getCurrentCount(String key) {
        TokenBucket bucket = getCurrentState(key);
        return bucket == null ? -1 : (long) bucket.getTokens();
    }

    @Override
    public void reset(String key) {
        TokenBucket bucket = getCurrentState(key);
        if (bucket != null) {
            bucket.reset();
            log.debug("重置令牌桶 - key: {}", key);
        }
    }

    @Override
    public void resetAll() {
        clearStates();
        log.info("清空所有令牌桶");
    }

    /** 当前令牌桶数量（供管理接口展示）。 */
    public int getBucketCount() {
        return getStateCount();
    }

    @Override
    protected boolean isExpired(TokenBucket state, long now) {
        // 距上次访问超过一个窗口：桶内令牌早已按速率补满，删除后重建为满桶，限流语义等价
        return now - state.lastAccess >= state.windowSeconds * 1000L;
    }

    @Override
    protected long accessTime(TokenBucket state) {
        return state.lastAccess;
    }

    @Override
    protected boolean matches(TokenBucket state, int limit, int windowSeconds) {
        return state.capacity == limit && state.windowSeconds == windowSeconds;
    }

    @Override
    protected TokenBucket newState(int limit, int windowSeconds) {
        return new TokenBucket(limit, windowSeconds);
    }

    @Override
    protected void touch(TokenBucket state) {
        state.touch();
    }

    @Override
    protected void reset(TokenBucket state) {
        state.reset();
    }

    /**
     * 单个限流键的令牌桶状态。
     */
    static final class TokenBucket {

        /** 桶容量（最大令牌数）。 */
        final int capacity;

        /** 时间窗口（秒），用于换算补充速率。 */
        final int windowSeconds;

        /** 当前令牌数（double，允许小数令牌）。 */
        private double tokens;

        /** 上次补充令牌的时间戳（毫秒）。 */
        private long lastRefillTime;

        /** 最近访问时间戳（用于过期判断与 LRU 淘汰）。 */
        private volatile long lastAccess;

        TokenBucket(int capacity, int windowSeconds) {
            this.capacity = capacity;
            this.windowSeconds = Math.max(1, windowSeconds);
            this.tokens = capacity;
            this.lastRefillTime = System.currentTimeMillis();
            this.lastAccess = lastRefillTime;
        }

        void touch() {
            lastAccess = System.currentTimeMillis();
        }

        /**
         * 尝试获取一个令牌。
         *
         * @return true 获取成功，false 令牌不足
         */
        synchronized boolean tryAcquire() {
            refill();
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }

        /**
         * 按时间流逝补充令牌：速率 = capacity / windowSeconds（个/秒）。
         */
        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTime;
            if (elapsed <= 0) {
                return;
            }
            double refillRate = (double) capacity / windowSeconds;
            double toAdd = (elapsed / 1000.0) * refillRate;
            tokens = Math.min(capacity, tokens + toAdd);
            lastRefillTime = now;
        }

        synchronized double getTokens() {
            refill();
            return tokens;
        }

        synchronized void reset() {
            tokens = capacity;
            lastRefillTime = System.currentTimeMillis();
        }
    }
}
