package io.github.biglv666.apigovernance.aspect;

import io.github.biglv666.apigovernance.annotation.ConcurrencyLimit;
import io.github.biglv666.apigovernance.concurrency.LocalConcurrencyLimiter;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.exception.GovernanceException;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.filter.PreFilter;
import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRateRuleStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 切面并发准入测试：名额耗尽快速拒绝、异常路径归还、动态规则 maxConcurrent 覆盖。
 *
 * @author API Governance Team
 * @since 1.0.0
 */
class GovernanceAspectConcurrencyTest {

    @RestController
    static class TestController {

        @ConcurrencyLimit(permits = 1)
        @GetMapping("/limited")
        public String limited() {
            return "ok";
        }

        @ConcurrencyLimit(permits = 1)
        @GetMapping("/boom")
        public String boom() {
            throw new IllegalStateException("boom");
        }

        @GetMapping("/free")
        public String free() {
            return "ok";
        }
    }

    private static final String LIMIT_KEY = TestController.class.getName() + "#limited";

    private static FilterChain chain() {
        return new FilterChain(List.of((PreFilter) context -> true), List.of());
    }

    private static ProceedingJoinPoint joinPoint(String methodName, boolean failing,
                                                 CountDownLatch entered,
                                                 CountDownLatch release) throws Throwable {
        Method method = TestController.class.getDeclaredMethod(methodName);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getTarget()).thenReturn(new TestController());
        when(joinPoint.getArgs()).thenReturn(new Object[0]);
        when(joinPoint.proceed()).thenAnswer(invocation -> {
            if (entered != null) {
                entered.countDown();
            }
            if (release != null) {
                release.await(5, TimeUnit.SECONDS);
            }
            if (failing) {
                throw new IllegalStateException("boom");
            }
            return "ok";
        });
        return joinPoint;
    }

    @Test
    void rejectsWhenPermitExhaustedAndReleasesAfterCompletion() throws Throwable {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);
        GovernanceAspect aspect = new GovernanceAspect(chain(), new ApiGovernanceProperties(),
                null, limiter);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try {
                aspect.around(joinPoint("limited", false, entered, release));
            } catch (Throwable t) {
                firstError.set(t);
            }
        });
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(limiter.getActiveCount(LIMIT_KEY)).isEqualTo(1);

        // 第二个请求：拿不到名额，立即 429，不进入业务方法
        assertThatThrownBy(() -> aspect.around(joinPoint("limited", false, null, null)))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> {
                    GovernanceException ex = (GovernanceException) e;
                    assertThat(ex.getStatus()).isEqualTo(429);
                    assertThat(ex.getCode()).isEqualTo("CONCURRENCY_LIMIT");
                    assertThat(ex.getHeaders()).containsEntry("Retry-After", "1");
                });

        release.countDown();
        first.join(5000);
        assertThat(firstError.get()).isNull();
        assertThat(limiter.getActiveCount(LIMIT_KEY)).isZero();
        // 名额已归还，后续请求正常
        assertThat(aspect.around(joinPoint("limited", false, null, null))).isEqualTo("ok");
        assertThat(limiter.getActiveCount(LIMIT_KEY)).isZero();
    }

    @Test
    void releasesPermitWhenBusinessThrows() throws Throwable {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);
        GovernanceAspect aspect = new GovernanceAspect(chain(), new ApiGovernanceProperties(),
                null, limiter);

        assertThatThrownBy(() -> aspect.around(joinPoint("boom", true, null, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("boom");
        assertThat(limiter.getActiveCount(TestController.class.getName() + "#boom")).isZero();
    }

    @Test
    void noAnnotationNoConcurrency() throws Throwable {
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);
        GovernanceAspect aspect = new GovernanceAspect(chain(), new ApiGovernanceProperties(),
                null, limiter);

        assertThat(aspect.around(joinPoint("free", false, null, null))).isEqualTo("ok");
        assertThat(limiter.getActiveCount(TestController.class.getName() + "#free")).isZero();
        assertThat(limiter.getKeyCount()).isZero();
    }

    @Test
    void dynamicRuleMaxConcurrentApplies() throws Throwable {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        // limit=-1（不限流）+ maxConcurrent=1（并发上限）
        store.put(DynamicRateRule.of(LIMIT_KEY, -1, 1, null, "ops", "incident", 1));
        LocalConcurrencyLimiter limiter = new LocalConcurrencyLimiter(100, 60_000L);
        GovernanceAspect aspect = new GovernanceAspect(chain(), new ApiGovernanceProperties(),
                store, limiter);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> firstError = new AtomicReference<>();

        Thread first = new Thread(() -> {
            try {
                aspect.around(joinPoint("limited", false, entered, release));
            } catch (Throwable t) {
                firstError.set(t);
            }
        });
        first.start();
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();

        assertThatThrownBy(() -> aspect.around(joinPoint("limited", false, null, null)))
                .isInstanceOf(GovernanceException.class)
                .satisfies(e -> assertThat(((GovernanceException) e).getStatus()).isEqualTo(429));

        release.countDown();
        first.join(5000);
        assertThat(firstError.get()).isNull();
        assertThat(limiter.getActiveCount(LIMIT_KEY)).isZero();
    }
}
