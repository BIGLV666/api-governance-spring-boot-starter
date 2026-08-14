package org.example.apigovernancespringbootstarter.ratelimit;

import org.example.apigovernancespringbootstarter.ratelimit.local.SlidingWindowRateLimiter;
import org.example.apigovernancespringbootstarter.ratelimit.local.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本机限流器单元测试：验证令牌桶与滑动窗口的基础限流行为。
 *
 * @author API Governance Team
 * @since 1.0
 */
class LocalRateLimiterTest {

    @Test
    void tokenBucketEnforcesLimit() {
        RateLimiter limiter = new TokenBucketRateLimiter();
        assertTrue(limiter.tryAcquire("k", 2, 1));
        assertTrue(limiter.tryAcquire("k", 2, 1));
        assertFalse(limiter.tryAcquire("k", 2, 1));
    }

    @Test
    void slidingWindowEnforcesLimit() {
        RateLimiter limiter = new SlidingWindowRateLimiter();
        assertTrue(limiter.tryAcquire("k", 2, 1));
        assertTrue(limiter.tryAcquire("k", 2, 1));
        assertFalse(limiter.tryAcquire("k", 2, 1));
    }

    @Test
    void zeroLimitDeniesAll() {
        RateLimiter tokenBucket = new TokenBucketRateLimiter();
        RateLimiter slidingWindow = new SlidingWindowRateLimiter();
        assertFalse(tokenBucket.tryAcquire("k", 0, 1));
        assertFalse(slidingWindow.tryAcquire("k", 0, 1));
    }
}
