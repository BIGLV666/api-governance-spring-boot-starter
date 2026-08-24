package io.github.biglv666.apigovernance.filter.impl;

import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PreFilter;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;

/**
 * 流量统计过滤器（前置，order = 100）。
 *
 * <p>在请求进入业务方法之前，累加该 API 的「总请求数」，实现流量维度的统计。
 * 更细粒度的结果（成功/失败/耗时/慢方法）由后置的 {@link SlowMethodFilter} 记录。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class TrafficStatisticsFilter implements PreFilter {

    private final MetricsRegistry metricsRegistry;

    public TrafficStatisticsFilter(MetricsRegistry metricsRegistry) {
        this.metricsRegistry = metricsRegistry;
    }

    @Override
    public boolean doFilter(FilterContext context) {
        metricsRegistry.recordStart(context.getApiKey());
        return true;
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
