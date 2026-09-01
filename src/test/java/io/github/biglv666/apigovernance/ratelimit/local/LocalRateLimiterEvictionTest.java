package io.github.biglv666.apigovernance.ratelimit.local;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 本地限流器容量保护测试 —— 验证过期优先淘汰、LRU 兜底与键数量上限。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
class LocalRateLimiterEvictionTest {

    @Test
    void slidingWindowEvictsLeastRecentlyUsedAtCapacity() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(3);
        limiter.tryAcquire("a", 10, 60);
        limiter.tryAcquire("b", 10, 60);
        limiter.tryAcquire("c", 10, 60);
        // 达到上限后 LRU 淘汰 1 个最旧键再容纳新键：总数保持上限，新键创建成功
        assertThat(limiter.tryAcquire("d", 10, 60)).isTrue();
        assertThat(limiter.getWindowCount()).isEqualTo(3);
    }

    @Test
    void slidingWindowPrefersExpiredKeysOverLru() throws InterruptedException {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(1);
        limiter.tryAcquire("fresh", 10, 1);
        // 等待窗口滑出，使 "fresh" 过期（windowMillis=1000）
        Thread.sleep(1100);

        limiter.tryAcquire("new-key", 10, 60);
        // 过期键被优先清理，新键创建后映射仍为 1，且 "fresh" 已不在
        assertThat(limiter.getWindowCount()).isEqualTo(1);
        limiter.resetAll();
    }

    @Test
    void tokenBucketEvictsExpiredBucketsFirst() throws InterruptedException {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1);
        limiter.tryAcquire("stale", 10, 1);
        // 等待超过一个窗口：令牌已补满，状态与新建等价，可安全过期淘汰
        Thread.sleep(1100);

        limiter.tryAcquire("new-key", 10, 60);
        assertThat(limiter.getBucketCount()).isEqualTo(1);
        limiter.resetAll();
    }

    @Test
    void limitAndWindowChangeRecreatesState() {
        SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter(100);
        String key = "api";
        assertThat(limiter.tryAcquire(key, 1, 60)).isTrue();
        assertThat(limiter.tryAcquire(key, 1, 60)).isFalse();
        // 参数变化重建窗口：新阈值下重新计数
        assertThat(limiter.tryAcquire(key, 2, 60)).isTrue();
        assertThat(limiter.getCurrentCount(key)).isEqualTo(1);
    }

    @Test
    void tokenBucketAcquireAndReset() {
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(100);
        assertThat(limiter.tryAcquire("api", 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("api", 2, 60)).isTrue();
        assertThat(limiter.tryAcquire("api", 2, 60)).isFalse();
        assertThat(limiter.getCurrentCount("api")).isEqualTo(0);

        limiter.reset("api");
        assertThat(limiter.getCurrentCount("api")).isEqualTo(2);
    }
}
