package io.github.biglv666.apigovernance.concurrency;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 并发许可句柄 —— 一次成功获取的 bulkhead 名额。
 *
 * <p>必须在业务方法执行的 {@code finally} 中调用 {@link #release()} 归还名额
 * （{@code Semaphore} 不是 {@code AutoCloseable}，JDK 官方推荐的就是 try/finally）。
 * 释放是幂等的：重复调用不会导致信号量配额虚增。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
public final class ConcurrencyPermit {

    private final LocalConcurrencyLimiter.Bucket bucket;

    private final String key;

    private final AtomicBoolean released = new AtomicBoolean(false);

    ConcurrencyPermit(LocalConcurrencyLimiter.Bucket bucket, String key) {
        this.bucket = bucket;
        this.key = key;
    }

    /**
     * 归还名额（幂等）。
     */
    public void release() {
        if (released.compareAndSet(false, true)) {
            bucket.release();
        }
    }

    /**
     * 是否已归还。
     *
     * @return true 表示已释放
     */
    public boolean isReleased() {
        return released.get();
    }

    /**
     * 对应的限流键。
     *
     * @return API 标识
     */
    public String getKey() {
        return key;
    }
}
