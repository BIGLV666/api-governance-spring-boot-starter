package io.github.biglv666.apigovernance.aspect;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PreFilter;
import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRateRuleStore;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 动态限流规则切面覆盖测试：验证「动态规则 &gt; 方法注解 &gt; 类注解 &gt; 全局默认」
 * 优先级与 -1（放开）/ 0（封禁）/ &gt;0（限流）三种语义，以及无规则路径零变化。
 *
 * <p>与 {@code SpelRateLimitKeyTest} 同构：真实切面 + 捕获型前置过滤器，
 * 断言 {@link FilterContext} 中的限流参数。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
class GovernanceAspectDynamicRuleTest {

    @RestController
    static class TestController {

        @RateLimit(limit = 5)
        @GetMapping("/annotated")
        public String annotated() {
            return "ok";
        }

        @RateLimit(limit = 5, key = "#userId")
        @GetMapping("/spel")
        public String spel(Long userId) {
            return "ok";
        }

        @RateLimit(limit = 3)
        @GetMapping("/override")
        public String override() {
            return "ok";
        }

        @GetMapping("/free")
        public String free() {
            return "ok";
        }
    }

    private final List<FilterContext> captured = new ArrayList<>();

    private FilterContext runAround(RateRuleStore store, String methodName, Object... args)
            throws Throwable {
        captured.clear();
        FilterChain chain = new FilterChain(
                List.of((PreFilter) ctx -> {
                    captured.add(ctx);
                    return true;
                }),
                List.of());
        GovernanceAspect aspect = new GovernanceAspect(chain, new ApiGovernanceProperties(), store);

        MethodSignature signature = mock(MethodSignature.class);
        Class<?>[] paramTypes = methodName.equals("spel") ? new Class<?>[]{Long.class}
                : new Class<?>[0];
        java.lang.reflect.Method method = TestController.class.getDeclaredMethod(methodName, paramTypes);
        when(signature.getMethod()).thenReturn(method);

        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(signature);
        when(pjp.getTarget()).thenReturn(new TestController());
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn("ok");

        aspect.around(pjp);
        return captured.get(0);
    }

    private static final String CTRL = TestController.class.getName();

    @Test
    void noStoreKeepsAnnotationBehavior() throws Throwable {
        FilterContext context = runAround(null, "annotated");
        assertTrue(context.isRateLimitEnabled());
        assertEquals(5, context.getRateLimit());
    }

    @Test
    void noMatchingRuleKeepsAnnotationBehavior() throws Throwable {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        store.put("com.other.Controller#get", 100, 60);
        FilterContext context = runAround(store, "annotated");
        assertTrue(context.isRateLimitEnabled());
        assertEquals(5, context.getRateLimit());
    }

    @Test
    void ruleOverridesMethodAnnotationLimit() throws Throwable {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        store.put(CTRL + "#override", 100, 60);
        FilterContext context = runAround(store, "override");
        assertTrue(context.isRateLimitEnabled());
        assertEquals(100, context.getRateLimit());
        assertEquals(60, context.getWindow());
    }

    @Test
    void ruleMinusOneDisablesAnnotatedLimit() throws Throwable {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        store.put(CTRL + "#override", -1, 60);
        FilterContext context = runAround(store, "override");
        assertFalse(context.isRateLimitEnabled());
        assertEquals(-1, context.getRateLimit());
    }

    @Test
    void ruleZeroBansApi() throws Throwable {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        store.put(CTRL + "#override", 0, 60);
        FilterContext context = runAround(store, "override");
        assertTrue(context.isRateLimitEnabled());
        assertEquals(0, context.getRateLimit());
    }

    @Test
    void ruleEnablesLimitOnPreviouslyUnlimitedApi() throws Throwable {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        store.put(CTRL + "#free", 7, 30);
        FilterContext context = runAround(store, "free");
        assertTrue(context.isRateLimitEnabled());
        assertEquals(7, context.getRateLimit());
        assertEquals(30, context.getWindow());
    }

    @Test
    void prefixRuleMatchesByStartsWith() throws Throwable {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        store.put(CTRL + "#*", 11, 10);
        FilterContext context = runAround(store, "override");
        assertTrue(context.isRateLimitEnabled());
        assertEquals(11, context.getRateLimit());
    }

    @Test
    void ruleDoesNotAffectSpelKeySuffix() throws Throwable {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        store.put(CTRL + "#spel", 9, 10);
        FilterContext context = runAround(store, "spel", 42L);
        assertEquals(9, context.getRateLimit());
        assertEquals("42", context.getRateLimitKeySuffix());
    }

    @Test
    void storeRulesAreVisibleThroughAll() {
        InMemoryRateRuleStore store = new InMemoryRateRuleStore(10);
        store.put(CTRL + "#override", 100, 60);
        DynamicRateRule rule = store.all().get(CTRL + "#override");
        assertEquals(100, rule.getLimit());
        assertEquals(60, rule.getWindow());
        assertTrue(rule.getUpdatedAtMs() > 0);
    }
}
