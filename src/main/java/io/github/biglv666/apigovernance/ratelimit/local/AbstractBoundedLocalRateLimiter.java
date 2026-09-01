package io.github.biglv666.apigovernance.ratelimit.local;

import io.github.biglv666.apigovernance.ratelimit.RateLimiter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 有界本地限流器基类 —— 封装「键数量上限保护 + 淘汰策略」，子类只实现具体算法状态。
 *
 * <h3>淘汰策略（容量保护）</h3>
 * <ol>
 *   <li>键数量未达上限时不做任何扫描（热路径零开销）；</li>
 *   <li>达到上限后<b>优先批量清理已过期的键</b>（窗口早已滑出、状态不可再用的键），
 *       这在 SpEL 参数维度限流等高基数场景下是绝大多数情况；</li>
 *   <li>无过期键时按 LRU 淘汰最旧的一批（约 1/16），摊薄持续新增键时的淘汰开销；</li>
 *   <li>两次全表扫描之间强制最小间隔 {@value #EVICT_MIN_INTERVAL_NANOS}ns，
 *       避免上限附近每次新建键都触发扫描；间隔内的短暂超限为软上限，仅影响内存占用边界，
 *       不影响任何键的限流判定正确性。</li>
 * </ol>
 *
 * @param <T> 单个限流键的算法状态类型
 * @author API Governance Team
 * @since 0.3.0
 */
abstract class AbstractBoundedLocalRateLimiter<T> implements RateLimiter {

    /** 两次淘汰全表扫描之间的最小间隔（纳秒），防止上限附近每请求都扫描。 */
    private static final long EVICT_MIN_INTERVAL_NANOS = 50_000_000L;

    /** 默认最大限流键数量。 */
    protected static final int DEFAULT_MAX_ENTRIES = 10_000;

    /** 最大限流键数量。 */
    private final int maxEntries;

    /** key -> 算法状态 的并发映射。 */
    private final Map<String, T> states = new ConcurrentHashMap<>();

    /** 上次淘汰扫描的时间（纳秒），用于节流。 */
    private volatile long lastEvictScanNanos;

    /**
     * 构造并指定最大键数量上限。
     *
     * @param maxEntries 最大限流键数量（小于 1 时按 1 处理）
     */
    protected AbstractBoundedLocalRateLimiter(int maxEntries) {
        this.maxEntries = Math.max(1, maxEntries);
    }

    /**
     * 获取或创建指定键的算法状态，并处理限流参数变化时的状态重建。
     *
     * @param key           限流键
     * @param limit         限流阈值
     * @param windowSeconds 时间窗口（秒）
     * @return 该键对应的算法状态（保证参数匹配）
     */
    protected final T acquireState(String key, int limit, int windowSeconds) {
        T state = states.get(key);
        if (state == null) {
            evictIfNeeded();
            T created = newState(limit, windowSeconds);
            T existing = states.putIfAbsent(key, created);
            return existing != null ? existing : created;
        }
        touch(state);
        // 限流参数变化时重建状态，使注解/配置变更即时生效
        if (!matches(state, limit, windowSeconds)) {
            T replacement = newState(limit, windowSeconds);
            states.put(key, replacement);
            return replacement;
        }
        return state;
    }

    /**
     * 获取已存在的键状态（不创建、不触发淘汰）；不存在时返回 null。
     */
    protected final T getCurrentState(String key) {
        return states.get(key);
    }

    /**
     * 当前键数量（供管理接口展示）。
     */
    protected final int getStateCount() {
        return states.size();
    }

    /**
     * 清空全部键状态。
     */
    protected final void clearStates() {
        states.clear();
    }

    /**
     * 容量保护：键数量达到上限时触发淘汰。
     * 策略见类注释 —— 过期优先批量清理，不足再 LRU 淘汰一批，扫描间隔节流。
     */
    private void evictIfNeeded() {
        if (states.size() < maxEntries) {
            return;
        }
        long nowNanos = System.nanoTime();
        if (nowNanos - lastEvictScanNanos < EVICT_MIN_INTERVAL_NANOS) {
            // 节流窗口内跳过扫描：短暂软超限，不影响限流判定正确性
            return;
        }
        lastEvictScanNanos = nowNanos;

        long now = System.currentTimeMillis();
        // 1) 批量清理已过期键（最常见路径：状态窗口早已滑出，删除无副作用）
        List<String> expired = new ArrayList<>();
        for (Map.Entry<String, T> entry : states.entrySet()) {
            if (isExpired(entry.getValue(), now)) {
                expired.add(entry.getKey());
            }
        }
        if (!expired.isEmpty()) {
            for (String key : expired) {
                states.remove(key);
            }
            if (states.size() < maxEntries) {
                return;
            }
        }
        // 2) 无过期键时按 LRU 淘汰最旧的一批（约 1/16），摊薄持续新增键时的扫描开销
        List<T> snapshot = new ArrayList<>(states.values());
        snapshot.sort(Comparator.comparingLong(this::accessTime));
        int toRemove = Math.max(1, snapshot.size() / 16);
        for (int i = 0; i < toRemove && i < snapshot.size(); i++) {
            removeState(snapshot.get(i));
        }
    }

    /** 按「最近访问时间」从映射中移除指定状态（O(n) 定位键）。 */
    private void removeState(T state) {
        for (Map.Entry<String, T> entry : states.entrySet()) {
            if (entry.getValue() == state) {
                states.remove(entry.getKey());
                return;
            }
        }
    }

    /**
     * 判断指定状态是否已过期（可安全删除：其限流语义与新建状态等价）。
     *
     * @param state 算法状态
     * @param now   当前时间戳（毫秒）
     */
    protected abstract boolean isExpired(T state, long now);

    /**
     * 获取状态的最近访问时间（毫秒），用于 LRU 淘汰排序。
     */
    protected abstract long accessTime(T state);

    /**
     * 状态是否与当前限流参数匹配（不匹配时重建）。
     */
    protected abstract boolean matches(T state, int limit, int windowSeconds);

    /**
     * 创建新的算法状态。
     */
    protected abstract T newState(int limit, int windowSeconds);

    /**
     * 刷新状态的最近访问时间。
     */
    protected abstract void touch(T state);

    /**
     * 重置状态到初始值（不删除键）。
     */
    protected abstract void reset(T state);
}
