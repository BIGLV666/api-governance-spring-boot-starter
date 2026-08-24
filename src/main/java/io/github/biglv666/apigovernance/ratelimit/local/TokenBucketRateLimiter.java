package io.github.biglv666.apigovernance.ratelimit.local;

import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 *   <li>桶数量以「限流键」为维度，并设置最大键数上限 + LRU 淘汰，防止内存膨胀。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class TokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(TokenBucketRateLimiter.class);

    /** 默认最大限流键数量（超出后按 LRU 淘汰最久未使用的键）。 */
    private static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** 最大限流键数量。 */
    private final int maxEntries;

    /** key -> 令牌桶 的并发映射。 */
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    /** 使用默认键数量上限构造。 */
    public TokenBucketRateLimiter() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * 构造并指定最大键数量上限。
     *
     * @param maxEntries 最大限流键数量
     */
    public TokenBucketRateLimiter(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        TokenBucket bucket = buckets.get(key);
        if (bucket == null) {
            bucket = createBucket(key, limit, windowSeconds);
        } else {
            bucket.touch();
            // 限流参数变化时重建桶，使注解/配置变更即时生效
            if (bucket.capacity != limit || bucket.windowSeconds != windowSeconds) {
                bucket = new TokenBucket(limit, windowSeconds);
                buckets.put(key, bucket);
            }
        }
        return bucket.tryAcquire();
    }

    @Override
    public String getName() {
        return "token-bucket-local";
    }

    @Override
    public long getCurrentCount(String key) {
        TokenBucket bucket = buckets.get(key);
        return bucket == null ? -1 : (long) bucket.getTokens();
    }

    @Override
    public void reset(String key) {
        TokenBucket bucket = buckets.get(key);
        if (bucket != null) {
            bucket.reset();
            log.debug("重置令牌桶 - key: {}", key);
        }
    }

    @Override
    public void resetAll() {
        buckets.clear();
        log.info("清空所有令牌桶");
    }

    /** 当前令牌桶数量（供管理接口展示）。 */
    public int getBucketCount() {
        return buckets.size();
    }

    /**
     * 创建令牌桶（含容量保护）。
     */
    private TokenBucket createBucket(String key, int limit, int windowSeconds) {
        evictIfNeeded();
        TokenBucket created = new TokenBucket(limit, windowSeconds);
        TokenBucket existing = buckets.putIfAbsent(key, created);
        return existing != null ? existing : created;
    }

    /**
     * 容量保护：超过键数量上限时淘汰最久未使用的键。
     * 仅在新增键时触发，属于低频路径，允许 O(n) 扫描。
     */
    private void evictIfNeeded() {
        if (buckets.size() < maxEntries) {
            return;
        }
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, TokenBucket> entry : buckets.entrySet()) {
            long access = entry.getValue().lastAccess;
            if (access < oldestTime) {
                oldestTime = access;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            buckets.remove(oldestKey);
        }
    }

    /**
     * 单个限流键的令牌桶状态。
     */
    private static final class TokenBucket {

        /** 桶容量（最大令牌数）。 */
        private final int capacity;

        /** 时间窗口（秒），用于换算补充速率。 */
        private final int windowSeconds;

        /** 当前令牌数（double，允许小数令牌）。 */
        private double tokens;

        /** 上次补充令牌的时间戳（毫秒）。 */
        private long lastRefillTime;

        /** 最近访问时间戳（用于 LRU 淘汰）。 */
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
