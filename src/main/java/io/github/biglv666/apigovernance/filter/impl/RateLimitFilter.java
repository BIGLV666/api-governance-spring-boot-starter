package io.github.biglv666.apigovernance.filter.impl;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.FilterContext;
import io.github.biglv666.apigovernance.filter.PreFilter;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimitRejectHandler;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.RateLimiterUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 限流判断过滤器（前置，order = 200）。
 *
 * <p>根据上下文中解析出的限流配置（注解 &gt; 全局默认），调用限流器插件执行判断：
 * <ul>
 *   <li>未启用限流：直接放行；</li>
 *   <li>限流器缺失：记录告警并放行（降级，避免拖垮业务）；</li>
 *   <li>被限流：先调用 {@link RateLimitRejectHandler}（注册 Bean 可自定义响应行为），
 *       再记录拒绝指标并返回 false（短路）；</li>
 *   <li>限流器故障且 fail-strategy=close：限流器抛出
 *       {@link RateLimiterUnavailableException}，转换为 503 拒绝（与 429 限流区分）。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class RateLimitFilter implements PreFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** 限流器故障（fail-close）拒绝时的默认 HTTP 状态码。 */
    private static final int UNAVAILABLE_STATUS = 503;

    private final RateLimiter rateLimiter;
    private final MetricsRegistry metricsRegistry;
    private final ApiGovernanceProperties properties;
    private final RateLimitKeyResolver keyResolver;
    private final RateLimitRejectHandler rejectHandler;

    /**
     * 构造限流过滤器。
     *
     * @param rateLimiter     限流器插件（可能为 null，表示未配置）
     * @param metricsRegistry 指标注册表
     * @param properties      全局配置
     * @param keyResolver     限流键解析器（决定限流颗粒度）
     * @param rejectHandler   限流拒绝处理器（可为 null，为 null 时使用内置默认行为）
     */
    public RateLimitFilter(RateLimiter rateLimiter, MetricsRegistry metricsRegistry,
                           ApiGovernanceProperties properties, RateLimitKeyResolver keyResolver,
                           RateLimitRejectHandler rejectHandler) {
        this.rateLimiter = rateLimiter;
        this.metricsRegistry = metricsRegistry;
        this.properties = properties;
        this.keyResolver = keyResolver;
        this.rejectHandler = rejectHandler;
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

        // 由键解析器决定限流颗粒度（默认接口级，可切换为用户级 / SpEL 参数级等）
        String rateLimitKey = keyResolver.resolve(context);

        boolean pass;
        try {
            pass = rateLimiter.tryAcquire(rateLimitKey,
                    context.getRateLimit(), context.getWindow());
        } catch (RateLimiterUnavailableException e) {
            // 限流器故障且 fail-strategy=close：按 503 拒绝，与普通 429 限流区分
            return reject(context, rateLimitKey, UNAVAILABLE_STATUS, "限流服务暂不可用，请稍后重试");
        }

        if (!pass) {
            rejectWithHandler(context, rateLimitKey);
        }
        return pass;
    }

    /**
     * 处理一次普通限流拒绝：委托 {@link RateLimitRejectHandler} 自定义（若注册），
     * 否则按 yml 配置的默认状态码与提示语；随后统一记录拒绝指标。
     */
    private void rejectWithHandler(FilterContext context, String rateLimitKey) {
        if (rejectHandler != null) {
            try {
                rejectHandler.handleReject(context, rateLimitKey);
            } catch (Exception e) {
                // 处理器异常不影响短路语义，回退默认拒绝行为
                log.warn("限流拒绝处理器执行异常，回退默认拒绝 - handler: {}, 错误: {}",
                        rejectHandler.getClass().getName(), e.getMessage());
                applyDefaultReject(context);
            }
        } else {
            applyDefaultReject(context);
        }
        reject(context, rateLimitKey, context.getRejectStatus(),
                context.getRejectReason() != null ? context.getRejectReason()
                        : properties.getRateLimit().getMessage());
    }

    /**
     * 记录拒绝指标与日志并短路请求（指标按接口维度统计，便于观察接口整体情况）。
     */
    private boolean reject(FilterContext context, String rateLimitKey, int status, String reason) {
        context.setRejectStatus(status);
        context.setRejectReason(reason);
        metricsRegistry.recordReject(context.getApiKey(), context.getHttpMethod(),
                context.getPath(), reason);
        log.warn("请求被拒绝 - key: {}, API: {}, status: {}, 原因: {}",
                rateLimitKey, context.getApiKey(), status, reason);
        return false;
    }

    /**
     * 内置默认拒绝行为：使用全局配置的状态码与提示语。
     */
    private void applyDefaultReject(FilterContext context) {
        context.setRejectStatus(properties.getRateLimit().getStatusCode());
        context.setRejectReason(properties.getRateLimit().getMessage());
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
