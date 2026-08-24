package io.github.biglv666.apigovernance.filter.impl;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PreFilter;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限流判断过滤器（前置，order = 200）。
 *
 * <p>根据上下文中解析出的限流配置（注解 &gt; 全局默认），调用限流器插件执行判断：
 * <ul>
 *   <li>未启用限流：直接放行；</li>
 *   <li>限流器缺失：记录告警并放行（降级，避免拖垮业务）；</li>
 *   <li>被限流：设置拒绝原因、记录拒绝指标并返回 false（短路）。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class RateLimitFilter implements PreFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private final RateLimiter rateLimiter;
    private final MetricsRegistry metricsRegistry;
    private final ApiGovernanceProperties properties;
    private final RateLimitKeyResolver keyResolver;

    /**
     * 构造限流过滤器。
     *
     * @param rateLimiter     限流器插件（可能为 null，表示未配置）
     * @param metricsRegistry 指标注册表
     * @param properties      全局配置
     * @param keyResolver     限流键解析器（决定限流颗粒度）
     */
    public RateLimitFilter(RateLimiter rateLimiter, MetricsRegistry metricsRegistry,
                           ApiGovernanceProperties properties, RateLimitKeyResolver keyResolver) {
        this.rateLimiter = rateLimiter;
        this.metricsRegistry = metricsRegistry;
        this.properties = properties;
        this.keyResolver = keyResolver;
    }

    @Override
    public boolean doFilter(FilterContext context) {
        if (!context.isRateLimitEnabled()) {
            return true;
        }
        if (rateLimiter == null) {
            log.warn("已配置限流但未找到限流器 Bean - API: {}, limit: {}",
                    context.getApiKey(), context.getRateLimit());
            return true;
        }

        // 由键解析器决定限流颗粒度（默认接口级，可切换为用户级等）
        String rateLimitKey = keyResolver.resolve(context);

        boolean pass = rateLimiter.tryAcquire(rateLimitKey,
                context.getRateLimit(), context.getWindow());

        if (!pass) {
            String reason = properties.getRateLimit().getMessage();
            context.setRejectStatus(properties.getRateLimit().getStatusCode());
            context.setRejectReason(reason);
            // 指标仍按接口维度统计，便于观察接口整体情况
            metricsRegistry.recordReject(context.getApiKey(), context.getHttpMethod(),
                    context.getPath(), reason);
            log.warn("限流拒绝 - key: {}, API: {}, limit: {}/{}(秒)",
                    rateLimitKey, context.getApiKey(), context.getRateLimit(), context.getWindow());
        }
        return pass;
    }

    @Override
    public int getOrder() {
        return 200;
    }

    @Override
    public boolean isEnabled() {
        return rateLimiter != null;
    }
}
