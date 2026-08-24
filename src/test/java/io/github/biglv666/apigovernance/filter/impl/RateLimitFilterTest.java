package io.github.biglv666.apigovernance.filter.impl;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 限流过滤器单元测试：验证「限流键解析器」插件能切换限流颗粒度。
 *
 * @author API Governance Team
 * @since 1.0
 */
class RateLimitFilterTest {

    @Test
    void usesCustomKeyResolver() {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);

        MetricsRegistry registry = new MetricsRegistry(10, 10, 0);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        // 自定义键解析器：接口 + 用户维度
        RateLimitKeyResolver resolver = ctx -> ctx.getApiKey() + "#user:42";

        RateLimitFilter filter = new RateLimitFilter(rateLimiter, registry, properties, resolver);

        FilterContext context = mock(FilterContext.class);
        when(context.isRateLimitEnabled()).thenReturn(true);
        when(context.getApiKey()).thenReturn("com.example.UserController#get");
        when(context.getRateLimit()).thenReturn(10);
        when(context.getWindow()).thenReturn(1);

        assertTrue(filter.doFilter(context));
        // 验证限流键已切换为用户维度
        verify(rateLimiter).tryAcquire("com.example.UserController#get#user:42", 10, 1);
    }
}
