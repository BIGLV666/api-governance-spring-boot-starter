package io.github.biglv666.apigovernance.aspect;

import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PreFilter;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 捕获最后一个过滤上下文的自定义前置过滤器（order=50，位于内置过滤器之间）。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
@Component
@Order(50)
public class CapturingGovernanceFilter implements PreFilter {

    /** 最近一次进入前置链的上下文（测试断言用）。 */
    public static volatile FilterContext last;

    @Override
    public boolean doFilter(FilterContext context) {
        last = context;
        return true;
    }
}
