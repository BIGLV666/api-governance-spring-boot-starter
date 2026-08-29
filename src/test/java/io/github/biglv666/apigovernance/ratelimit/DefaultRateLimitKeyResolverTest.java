package io.github.biglv666.apigovernance.ratelimit;

import io.github.biglv666.apigovernance.filter.FilterContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 默认限流键解析器单元测试：验证参数维度后缀的拼接规则。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
class DefaultRateLimitKeyResolverTest {

    private final DefaultRateLimitKeyResolver resolver = new DefaultRateLimitKeyResolver();

    @Test
    void withoutSuffixUsesApiKeyOnly() {
        FilterContext context = mock(FilterContext.class);
        when(context.getApiKey()).thenReturn("com.x.UserController#get");
        when(context.getRateLimitKeySuffix()).thenReturn(null);

        assertEquals("com.x.UserController#get", resolver.resolve(context));
    }

    @Test
    void withSuffixAppendsParameterDimension() {
        FilterContext context = mock(FilterContext.class);
        when(context.getApiKey()).thenReturn("com.x.UserController#get");
        when(context.getRateLimitKeySuffix()).thenReturn("42");

        // 最终键 = 接口键:参数值，不同参数各自拥有独立配额
        assertEquals("com.x.UserController#get:42", resolver.resolve(context));
    }
}
