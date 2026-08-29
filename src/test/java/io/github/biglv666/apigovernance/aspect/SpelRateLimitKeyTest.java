package io.github.biglv666.apigovernance.aspect;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PreFilter;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SpEL 限流键单元测试：验证 @RateLimit(key = "...") 的解析、求值、安全限制与降级。
 *
 * <p>通过真实的 {@link GovernanceAspect} 管道执行，用一个前置过滤器捕获切面构建的
 * {@link FilterContext}，断言参数维度限流键后缀。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
class SpelRateLimitKeyTest {

    /**
     * 测试控制器：覆盖参数维度键、#apiKey 变量、表达式非法三类场景。
     */
    static class TestController {

        @RateLimit(limit = 5, key = "#userId")
        @GetMapping("/g")
        public String get(Long userId) {
            return "ok";
        }

        @RateLimit(limit = 5, key = "'api:' + #userId")
        @GetMapping("/tpl")
        public String tpl(Long userId) {
            return "ok";
        }

        @RateLimit(limit = 5, key = "T(java.lang.Runtime).getRuntime()")
        @GetMapping("/sec")
        public String sec() {
            return "ok";
        }

        @RateLimit(limit = 5, key = "#missing")
        @GetMapping("/err")
        public String err(Long userId) {
            return "ok";
        }

        @RateLimit(limit = 5)
        @GetMapping("/plain")
        public String plain(Long userId) {
            return "ok";
        }
    }

    private final List<FilterContext> captured = new ArrayList<>();

    private FilterContext runAround(String methodName, Object... args) throws Throwable {
        captured.clear();
        FilterChain chain = new FilterChain(
                List.of((PreFilter) ctx -> {
                    captured.add(ctx);
                    return true;
                }),
                List.of());
        GovernanceAspect aspect = new GovernanceAspect(chain, new ApiGovernanceProperties());

        MethodSignature signature = mock(MethodSignature.class);
        Class<?>[] paramTypes = methodName.equals("sec") ? new Class<?>[0] : new Class<?>[]{Long.class};
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

    @Test
    void keyExpressionBindsMethodParameter() throws Throwable {
        FilterContext context = runAround("get", 42L);
        assertEquals("42", context.getRateLimitKeySuffix());
    }

    @Test
    void keyExpressionSupportsConcatenation() throws Throwable {
        FilterContext context = runAround("tpl", 42L);
        assertEquals("api:42", context.getRateLimitKeySuffix());
    }

    @Test
    void typeReferenceIsBlockedBySimpleEvaluationContext() throws Throwable {
        // 安全边界：T() 类型引用在受限求值上下文中被拒绝，回退接口级限流而非抛出
        FilterContext context = runAround("sec");
        assertNull(context.getRateLimitKeySuffix());
    }

    @Test
    void unknownVariableFallsBackToMethodLevel() throws Throwable {
        FilterContext context = runAround("err", 42L);
        // 表达式求值结果为 null：不写后缀，回退接口级限流
        assertNull(context.getRateLimitKeySuffix());
    }

    @Test
    void noKeyAttributeMeansNoSuffix() throws Throwable {
        FilterContext context = runAround("plain", 42L);
        assertNull(context.getRateLimitKeySuffix());
    }
}
