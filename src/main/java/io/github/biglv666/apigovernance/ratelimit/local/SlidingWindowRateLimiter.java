package io.github.biglv666.apigovernance.ratelimit.local;

import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>键数量设置最大上限 + LRU 淘汰，防止映射膨胀。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class SlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(SlidingWindowRateLimiter.class);

    /** 默认最大限流键数量。 */
    private static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** 最大限流键数量。 */
    private final int maxEntries;

    /** key -> 滑动窗口 的并发映射。 */
    private final Map<String, SlidingWindow> windows = new ConcurrentHashMap<>();

    /** 使用默认键数量上限构造。 */
    public SlidingWindowRateLimiter() {
        this(DEFAULT_MAX_ENTRIES);
    }

    /**
     * 构造并指定最大键数量上限。
     *
     * @param maxEntries 最大限流键数量
     */
    public SlidingWindowRateLimiter(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        SlidingWindow window = windows.get(key);
        if (window == null) {
            window = createWindow(key, limit, windowSeconds);
        } else {
            window.touch();
            // 限流参数变化时重建窗口
            if (window.limit != limit || window.windowSeconds != windowSeconds) {
                window = new SlidingWindow(limit, windowSeconds);
                windows.put(key, window);
            }
        }
        return window.tryAcquire();
    }

    @Override
    public String getName() {
        return "sliding-window-local";
    }

    @Override
    public long getCurrentCount(String key) {
        SlidingWindow window = windows.get(key);
        return window == null ? 0 : window.getCount();
    }

    @Override
    public void reset(String key) {
        SlidingWindow window = windows.get(key);
        if (window != null) {
            window.reset();
            log.debug("重置滑动窗口 - key: {}", key);
        }
    }

    @Override
    public void resetAll() {
        windows.clear();
        log.info("清空所有滑动窗口");
    }

    /** 当前窗口数量（供管理接口展示）。 */
    public int getWindowCount() {
        return windows.size();
    }

    private SlidingWindow createWindow(String key, int limit, int windowSeconds) {
        evictIfNeeded();
        SlidingWindow created = new SlidingWindow(limit, windowSeconds);
        SlidingWindow existing = windows.putIfAbsent(key, created);
        return existing != null ? existing : created;
    }

    private void evictIfNeeded() {
        if (windows.size() < maxEntries) {
            return;
        }
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, SlidingWindow> entry : windows.entrySet()) {
            long access = entry.getValue().lastAccess;
            if (access < oldestTime) {
                oldestTime = access;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            windows.remove(oldestKey);
        }
    }

    /**
     * 单个限流键的滑动窗口状态。
     */
    private static final class SlidingWindow {

        /** 限流阈值。 */
        private final int limit;

        /** 时间窗口（秒）。 */
        private final int windowSeconds;

        /** 窗口长度（毫秒）。 */
        private final long windowMillis;

        /** 窗口内请求时间戳队列。 */
        private final ConcurrentLinkedDeque<Long> timestamps = new ConcurrentLinkedDeque<>();

        /** 最近访问时间戳（用于 LRU 淘汰）。 */
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
