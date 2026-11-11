package io.github.biglv666.apigovernance.concurrency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地并发限制器（按 key 分桶的信号量，进程内生效）。
 *
 * <p>每个 API 标识对应一个 {@link Semaphore}，{@link #tryAcquire} 非阻塞：
 * 达到并发上限立即返回 {@code null}，由调用方转为拒绝响应，<b>不排队、不占线程</b>。
 * 名额在业务方法的 {@code finally} 中归还，异常/超时路径同样释放。
 *
 * <h3>有界与空闲淘汰</h3>
 * <ul>
 *   <li>key 数量上限 {@code maxKeys}；按 {@code min(60s, idleTimeoutMs)} 的间隔扫描，
 *       淘汰空闲超过 {@code idleTimeoutMs} 的桶；</li>
 *   <li>只淘汰<b>无在途请求</b>的桶，绝不回收正在使用的名额；</li>
 *   <li>若全部桶都在使用且仍超过上限，允许临时增长并 warn —— 宁可短暂超出 key 上限，
 *       也不破坏并发上限的正确性。</li>
 * </ul>
 *
 * <h3>动态调整</h3>
 * <p>同一 key 的 permits 变化时（动态规则热更新）会创建新桶：新请求使用新配额，
 * 已在途的旧请求仍向旧桶归还，互不影响。切换瞬间的最坏情况是
 * 「旧在途数 + 新配额」短暂高于新配额，属于规则热更新的可接受语义。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
public class LocalConcurrencyLimiter implements ConcurrencyLimiter {

    private static final Logger log = LoggerFactory.getLogger(LocalConcurrencyLimiter.class);

    /** 空闲扫描的最大间隔，避免每个请求都遍历 map。 */
    private static final long MAX_PRUNE_INTERVAL_MS = 60_000L;

    private final int maxKeys;

    private final long idleTimeoutMs;

    /** 实际扫描间隔：不超过 60s，也不超过 idleTimeout，保证淘汰时延可控。 */
    private final long pruneIntervalMs;

    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    private final AtomicLong lastPruneMs = new AtomicLong();

    private final AtomicBoolean pruning = new AtomicBoolean(false);

    /**
     * 构造本地并发限制器。
     *
     * @param maxKeys       维护的 key 数量上限（至少 1）
     * @param idleTimeoutMs 空闲桶淘汰阈值（毫秒，至少 1000）
     */
    public LocalConcurrencyLimiter(int maxKeys, long idleTimeoutMs) {
        this.maxKeys = Math.max(1, maxKeys);
        this.idleTimeoutMs = Math.max(1000L, idleTimeoutMs);
        this.pruneIntervalMs = Math.min(MAX_PRUNE_INTERVAL_MS, this.idleTimeoutMs);
    }

    @Override
    public ConcurrencyPermit tryAcquire(String key, int permits) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("并发限流 key 不能为空");
        }
        if (permits <= 0) {
            throw new IllegalArgumentException("并发上限 permits 必须大于 0，收到: " + permits);
        }
        // 桶可能在 acquire 与 prune 之间被"退休"：此时重试拿新桶，
        // 绝不在已脱离 map 的桶上放行，否则会突破并发上限
        for (int attempt = 0; attempt < 3; attempt++) {
            Bucket bucket = bucket(key, permits);
            if (bucket.tryAcquire()) {
                pruneIfNeeded();
                return new ConcurrencyPermit(bucket, key);
            }
            if (!bucket.isRetired()) {
                // 桶仍在使用中且名额已满
                return null;
            }
        }
        return null;
    }

    @Override
    public int getActiveCount(String key) {
        Bucket bucket = key == null ? null : buckets.get(key);
        return bucket == null ? 0 : bucket.activeCount();
    }

    @Override
    public int getKeyCount() {
        return buckets.size();
    }

    @Override
    public String getName() {
        return "local";
    }

    /** 当前配置的 key 上限（观测用）。 */
    public int getMaxKeys() {
        return maxKeys;
    }

    /** 当前配置的空闲淘汰阈值（毫秒）。 */
    public long getIdleTimeoutMs() {
        return idleTimeoutMs;
    }

    private Bucket bucket(String key, int permits) {
        Bucket existing = buckets.get(key);
        if (existing != null && existing.permits == permits && !existing.isRetired()) {
            return existing;
        }
        return buckets.compute(key, (k, current) ->
                current != null && current.permits == permits && !current.isRetired()
                        ? current : new Bucket(permits));
    }

    /**
     * 超过 key 上限或到达扫描间隔时清理空闲桶。
     *
     * <p>仅在无锁快照上遍历；删除使用 {@code remove(key, bucket)} 避免误删并发替换的新桶。
     */
    private void pruneIfNeeded() {
        long now = System.currentTimeMillis();
        long last = lastPruneMs.get();
        // 无论是否超限都遵守扫描间隔，避免过载时每个请求都全表扫描 + WARN
        if (now - last < pruneIntervalMs) {
            return;
        }
        if (!lastPruneMs.compareAndSet(last, now)) {
            return;
        }
        if (!pruning.compareAndSet(false, true)) {
            return;
        }
        try {
            for (Map.Entry<String, Bucket> entry : buckets.entrySet()) {
                Bucket bucket = entry.getValue();
                if (bucket.tryRetire(now, idleTimeoutMs)) {
                    buckets.remove(entry.getKey(), bucket);
                }
            }
            if (buckets.size() > maxKeys) {
                log.warn("并发限流 key 数超过上限 - 当前: {}, 上限: {}；所有桶均有在途请求，"
                        + "暂不淘汰（优先保证并发上限正确性）", buckets.size(), maxKeys);
            }
        } finally {
            pruning.set(false);
        }
    }

    /**
     * 单个 key 的信号量桶。
     *
     * <p>{@link #tryAcquire()}、{@link #release()} 与 {@link #tryRetire(long, long)}
     * 都在桶实例上加锁：要么请求先占住名额（淘汰失败），要么淘汰先置 {@code retired}
     * （请求失败并换新桶重试），不会出现"在已脱离 map 的桶上放行"导致超卖。
     */
    static final class Bucket {

        private final int permits;

        private final Semaphore semaphore;

        private long lastAccessMs = System.currentTimeMillis();

        /** 当前在途请求数（受 this 锁保护）。 */
        private int activeCount;

        /** 是否已被淘汰（受 this 锁保护）。 */
        private boolean retired;

        Bucket(int permits) {
            this.permits = permits;
            this.semaphore = new Semaphore(permits);
        }

        synchronized boolean tryAcquire() {
            if (retired || !semaphore.tryAcquire()) {
                return false;
            }
            activeCount++;
            lastAccessMs = System.currentTimeMillis();
            return true;
        }

        synchronized void release() {
            semaphore.release();
            if (activeCount > 0) {
                activeCount--;
            }
            lastAccessMs = System.currentTimeMillis();
        }

        synchronized int activeCount() {
            return activeCount;
        }

        synchronized boolean isRetired() {
            return retired;
        }

        /**
         * 尝试淘汰：仅当无在途请求且空闲超过阈值时成功。
         *
         * @param nowMs        当前时间
         * @param idleTimeoutMs 空闲阈值
         * @return true 表示已标记淘汰，调用方可以从 map 移除
         */
        synchronized boolean tryRetire(long nowMs, long idleTimeoutMs) {
            if (retired || activeCount > 0 || nowMs - lastAccessMs < idleTimeoutMs) {
                return false;
            }
            retired = true;
            return true;
        }
    }
}
