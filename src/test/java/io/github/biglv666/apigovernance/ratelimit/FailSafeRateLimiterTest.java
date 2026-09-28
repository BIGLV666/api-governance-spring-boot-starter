package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.alert.internal.AlertDispatcher;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流器故障降级装饰器单元测试：验证 open/close 两种降级策略与告警触发。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
class FailSafeRateLimiterTest {

    /**
     * 永远抛异常的限流器（模拟 Redis 宕机）。
     */
    static class BrokenRateLimiter implements RateLimiter {
        int attempts;

        @Override
        public boolean tryAcquire(String key, int limit, int windowSeconds) {
            attempts++;
            throw new IllegalStateException("connection refused");
        }

        @Override
        public String getName() {
            return "broken-redis";
        }
    }

    /**
     * 收集告警事件的最小分发器替身。
     */
    static class CapturingDispatcher extends AlertDispatcher {
        final List<String> failures = new java.util.ArrayList<>();
        final List<String> recoveries = new java.util.ArrayList<>();

        CapturingDispatcher() {
            super(List.of(), 0, 1000);
        }

        @Override
        public void publishRateLimiterFailure(String rateLimiterName, String error) {
            failures.add(rateLimiterName + ":" + error);
        }

        @Override
        public void markRecovered(io.github.biglv666.apigovernance.alert.GovernanceAlertEvent.Type type,
                                  String apiKey) {
            recoveries.add(type + ":" + apiKey);
        }
    }

    @Test
    void failOpenAllowsRequestOnFailure() {
        BrokenRateLimiter broken = new BrokenRateLimiter();
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(broken, false, null);

        // fail-open（默认）：故障放行，可用性优先
        assertTrue(limiter.tryAcquire("k", 10, 1));
        assertEquals(1, broken.attempts);
        assertEquals("broken-redis", limiter.getName());
    }

    @Test
    void failCloseRejectsRequestOnFailure() {
        BrokenRateLimiter broken = new BrokenRateLimiter();
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(broken, true, null);

        // fail-close：抛出不可用异常，由 RateLimitFilter 转换为 503 拒绝
        assertThrows(RateLimiterUnavailableException.class,
                () -> limiter.tryAcquire("k", 10, 1));
    }

    @Test
    void failurePublishesAlertEvent() {
        BrokenRateLimiter broken = new BrokenRateLimiter();
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(broken, false, dispatcher);

        limiter.tryAcquire("k", 10, 1);

        assertEquals(1, dispatcher.failures.size());
        assertTrue(dispatcher.failures.get(0).startsWith("broken-redis:"));
        assertTrue(dispatcher.failures.get(0).contains("connection refused"));
    }

    @Test
    void healthyDelegatePassesThrough() {
        RateLimiter healthy = new RateLimiter() {
            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                return false;
            }

            @Override
            public String getName() {
                return "healthy";
            }
        };
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(healthy, false, null);

        // 委托正常拒绝（返回 false）不等同于故障，不应触发降级逻辑
        assertFalse(limiter.tryAcquire("k", 10, 1));
    }

    @Test
    void firstSuccessAfterFailureSignalsRecovery() {
        // 0.6.0 恢复通知：故障后首次成功上报 RATE_LIMITER_FAILURE 恢复，且只报一次
        RateLimiter flaky = new RateLimiter() {
            int attempts;

            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                if (attempts++ == 0) {
                    throw new IllegalStateException("connection refused");
                }
                return true;
            }

            @Override
            public String getName() {
                return "flaky-redis";
            }
        };
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(flaky, false, dispatcher);

        // 第 1 次：故障（fail-open 放行）
        assertTrue(limiter.tryAcquire("k", 10, 1));
        assertEquals(1, dispatcher.failures.size());
        assertEquals(0, dispatcher.recoveries.size());

        // 第 2 次：成功 → 恢复信号
        assertTrue(limiter.tryAcquire("k", 10, 1));
        assertEquals(1, dispatcher.recoveries.size());
        assertEquals("RATE_LIMITER_FAILURE:flaky-redis", dispatcher.recoveries.get(0));

        // 第 3 次：继续成功 → 不重复上报
        assertTrue(limiter.tryAcquire("k", 10, 1));
        assertEquals(1, dispatcher.recoveries.size());
    }

    @Test
    void healthyLimiterNeverSignalsRecovery() {
        RateLimiter healthy = new RateLimiter() {
            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                return true;
            }

            @Override
            public String getName() {
                return "healthy-redis";
            }
        };
        CapturingDispatcher dispatcher = new CapturingDispatcher();
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(healthy, false, dispatcher);

        assertTrue(limiter.tryAcquire("k", 10, 1));
        assertTrue(limiter.tryAcquire("k", 10, 1));

        assertEquals(0, dispatcher.failures.size());
        assertEquals(0, dispatcher.recoveries.size());
    }
}
