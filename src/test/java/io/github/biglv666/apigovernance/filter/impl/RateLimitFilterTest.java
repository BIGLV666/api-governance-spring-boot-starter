package io.github.biglv666.apigovernance.filter.impl;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimitRejectHandler;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.RateLimiterUnavailableException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流过滤器单元测试：验证限流键解析器插件、拒绝处理器插件与限流器故障降级。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
class RateLimitFilterTest {

    /**
     * 构建真实的 FilterContext（拒绝状态码/原因需要真实状态，mock 无法记录 setter 写入）。
     */
    private FilterContext realContext(String apiKey) {
        try {
            java.lang.reflect.Method method = Object.class.getDeclaredMethod("toString");
            return new FilterContext(mock(ProceedingJoinPoint.class), apiKey, method, Object.class,
                    new Object[0]);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void usesCustomKeyResolver() {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);

        MetricsRegistry registry = new MetricsRegistry(10, 10, 0);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        // 自定义键解析器：接口 + 用户维度
        RateLimitKeyResolver resolver = ctx -> ctx.getApiKey() + "#user:42";

        RateLimitFilter filter = new RateLimitFilter(rateLimiter, registry, properties, resolver, null);

        FilterContext context = mock(FilterContext.class);
        when(context.isRateLimitEnabled()).thenReturn(true);
        when(context.getApiKey()).thenReturn("com.example.UserController#get");
        when(context.getRateLimit()).thenReturn(10);
        when(context.getWindow()).thenReturn(1);

        assertTrue(filter.doFilter(context));
        // 验证限流键已切换为用户维度
        verify(rateLimiter).tryAcquire("com.example.UserController#get#user:42", 10, 1);
    }

    @Test
    void defaultRejectUsesConfiguredStatusAndMessage() {
        RateLimiter rateLimiter = new RateLimiter() {
            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }
        };
        MetricsRegistry registry = new MetricsRegistry(10, 10, 0);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();

        RateLimitFilter filter = new RateLimitFilter(rateLimiter, registry, properties,
                FilterContext::getApiKey, null);

        FilterContext context = realContext("api");
        context.setRateLimitEnabled(true);
        context.setRateLimit(1);
        context.setWindow(1);

        assertFalse(filter.doFilter(context));
        // 默认行为与 0.1.0 一致：yml 配置的状态码与提示语
        assertEquals(429, context.getRejectStatus());
        assertEquals("请求过于频繁，请稍后重试", context.getRejectReason());
        // 拒绝指标已记录
        assertEquals(1, registry.get("api").getRejectRequests());
    }

    @Test
    void customRejectHandlerCanCustomizeResponse() {
        RateLimiter rateLimiter = new RateLimiter() {
            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }
        };
        MetricsRegistry registry = new MetricsRegistry(10, 10, 0);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        AtomicInteger seenLimit = new AtomicInteger();

        RateLimitRejectHandler handler = (ctx, key) -> {
            seenLimit.set(ctx.getRateLimit());
            ctx.setRejectStatus(420);
            ctx.setRejectReason("custom");
        };

        RateLimitFilter filter = new RateLimitFilter(rateLimiter, registry, properties,
                FilterContext::getApiKey, handler);

        FilterContext context = realContext("api");
        context.setRateLimitEnabled(true);
        context.setRateLimit(7);
        context.setWindow(1);

        assertFalse(filter.doFilter(context));
        assertEquals(7, seenLimit.get());
        assertEquals(420, context.getRejectStatus());
        assertEquals("custom", context.getRejectReason());
    }

    @Test
    void failingRejectHandlerFallsBackToDefault() {
        RateLimiter rateLimiter = new RateLimiter() {
            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                return false;
            }

            @Override
            public String getName() {
                return "test";
            }
        };
        MetricsRegistry registry = new MetricsRegistry(10, 10, 0);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();

        RateLimitRejectHandler handler = (ctx, key) -> {
            throw new IllegalStateException("boom");
        };

        RateLimitFilter filter = new RateLimitFilter(rateLimiter, registry, properties,
                FilterContext::getApiKey, handler);

        FilterContext context = realContext("api");
        context.setRateLimitEnabled(true);
        context.setRateLimit(1);
        context.setWindow(1);

        // 处理器异常不影响短路语义，回退默认拒绝
        assertFalse(filter.doFilter(context));
        assertEquals(429, context.getRejectStatus());
        assertEquals("请求过于频繁，请稍后重试", context.getRejectReason());
    }

    @Test
    void rateLimiterUnavailableRejectsWith503() {
        RateLimiter rateLimiter = new RateLimiter() {
            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                throw new RateLimiterUnavailableException("redis down", new RuntimeException());
            }

            @Override
            public String getName() {
                return "test";
            }
        };
        MetricsRegistry registry = new MetricsRegistry(10, 10, 0);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();

        RateLimitFilter filter = new RateLimitFilter(rateLimiter, registry, properties,
                FilterContext::getApiKey, null);

        FilterContext context = realContext("api");
        context.setRateLimitEnabled(true);
        context.setRateLimit(1);
        context.setWindow(1);

        // fail-strategy=close：限流器故障按 503 拒绝（与 429 限流区分），且记录拒绝指标
        assertFalse(filter.doFilter(context));
        assertEquals(503, context.getRejectStatus());
        assertEquals(1, registry.get("api").getRejectRequests());
    }
}
