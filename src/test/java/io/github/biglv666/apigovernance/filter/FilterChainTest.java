package io.github.biglv666.apigovernance.filter;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 过滤器链测试：验证前置过滤器异常的 fail-close 语义与拒绝状态码。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
class FilterChainTest {

    @Test
    void preFilterExceptionRejectsWith500InsteadOfDefault429() {
        // 回归：前置过滤器抛异常曾沿用 FilterContext 默认的 429 拒绝，
        // 「治理自身故障」被伪装成「限流拒绝」，排障方向被带偏
        PreFilter broken = new PreFilter() {
            @Override
            public boolean doFilter(FilterContext context) {
                throw new IllegalStateException("boom");
            }
        };
        FilterChain chain = new FilterChain(List.of(broken), List.of());
        FilterContext context = new FilterContext(null, "com.x.A#get", anyMethod(), Object.class, new Object[0]);

        boolean pass = chain.executePreFilters(context);

        assertThat(pass).isFalse();
        assertThat(context.getRejectStatus()).isEqualTo(500);
        assertThat(context.getRejectReason()).contains("过滤器异常");
    }

    private static Method anyMethod() {
        for (Method method : FilterChainTest.class.getDeclaredMethods()) {
            if (method.getName().equals("anyMethod")) {
                return method;
            }
        }
        throw new AssertionError("unreachable");
    }
}
