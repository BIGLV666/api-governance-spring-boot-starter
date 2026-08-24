package io.github.biglv666.apigovernance.filter.impl;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PostFilter;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 慢方法/指标统计过滤器（后置，order = 400）。
 *
 * <p>负责「记录耗时」与「更新统计」两件事：
 * <ul>
 *   <li>将本次请求的耗时、成功/失败、是否慢方法写入内存指标（{@link MetricsRegistry}）；</li>
 *   <li>当耗时超过阈值（{@code api.governance.log.slow-threshold-ms}）时输出慢方法告警日志。</li>
 * </ul>
 *
 * <p>被前置过滤器拒绝的请求（如限流）不会进入本过滤器，其指标已在拒绝处记录。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class SlowMethodFilter implements PostFilter {

    private static final Logger log = LoggerFactory.getLogger(SlowMethodFilter.class);

    private final MetricsRegistry metricsRegistry;
    private final ApiGovernanceProperties properties;

    public SlowMethodFilter(MetricsRegistry metricsRegistry, ApiGovernanceProperties properties) {
        this.metricsRegistry = metricsRegistry;
        this.properties = properties;
    }

    @Override
    public void doFilter(FilterContext context) {
        // 被拒绝的请求（限流等）已在其触发处记录，避免重复统计
        if (context.isRejected()) {
            return;
        }

        long elapsed = context.getElapsedTime();
        boolean success = context.getError() == null;
        long threshold = properties.getLog().getSlowThresholdMs();
        boolean slow = elapsed > threshold;
        String errorMsg = context.getError() == null ? null : context.getError().getMessage();

        metricsRegistry.recordResult(context.getApiKey(), elapsed, success, slow,
                context.getHttpMethod(), context.getPath(), errorMsg);

        if (slow) {
            log.warn("慢方法 - API: {} ({}) 耗时: {}ms 阈值: {}ms",
                    context.getApiKey(), context.getPath(), elapsed, threshold);
        }
    }

    @Override
    public int getOrder() {
        return 400;
    }
}
