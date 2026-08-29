package io.github.biglv666.apigovernance.config;

import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import io.github.biglv666.apigovernance.alert.internal.AlertDispatcher;
import io.github.biglv666.apigovernance.alert.webhook.WebhookAlertNotifier;
import io.github.biglv666.apigovernance.async.aspect.AsyncActionAspect;
import io.github.biglv666.apigovernance.async.internal.AsyncDispatcher;
import io.github.biglv666.apigovernance.async.internal.AsyncEventFactory;
import io.github.biglv666.apigovernance.async.internal.AsyncHandlerRegistry;
import io.github.biglv666.apigovernance.async.internal.DefaultAsyncExecutorProvider;
import io.github.biglv666.apigovernance.async.internal.LoggingAsyncHandlerExceptionHandler;
import io.github.biglv666.apigovernance.async.internal.LoggingAsyncTaskRejectionHandler;
import io.github.biglv666.apigovernance.async.internal.NoopAsyncTaskContextPropagator;
import io.github.biglv666.apigovernance.async.spi.AsyncEventEnricher;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.async.spi.AsyncHandlerExceptionHandler;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskRejectionHandler;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskContextPropagator;
import io.github.biglv666.apigovernance.aspect.GovernanceAspect;
import io.github.biglv666.apigovernance.exception.GovernanceExceptionHandler;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.filter.PostFilter;
import io.github.biglv666.apigovernance.filter.PreFilter;
import io.github.biglv666.apigovernance.filter.impl.LoggingFilter;
import io.github.biglv666.apigovernance.filter.impl.MetadataCollectorFilter;
import io.github.biglv666.apigovernance.filter.impl.RateLimitFilter;
import io.github.biglv666.apigovernance.filter.impl.SlowMethodFilter;
import io.github.biglv666.apigovernance.filter.impl.TrafficStatisticsFilter;
import io.github.biglv666.apigovernance.management.GovernanceManagementAuthFilter;
import io.github.biglv666.apigovernance.management.GovernanceManagementController;
import io.github.biglv666.apigovernance.metrics.MetricsEventListener;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.metrics.micrometer.MicrometerMetricsEventListener;
import io.github.biglv666.apigovernance.ratelimit.DefaultRateLimitKeyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimitAlgorithm;
import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimitRejectHandler;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.RateLimitStrategy;
import io.github.biglv666.apigovernance.ratelimit.StrategyRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.FailSafeRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.local.SlidingWindowRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.local.TokenBucketRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.redis.RedisSlidingWindowRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.redis.RedisTokenBucketRateLimiter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;

/**
 * API 治理自动配置 —— 「一切皆插件」的装配中心。
 *
 * <p>通过 Spring Boot 自动装配（见 {@code META-INF/spring/...AutoConfiguration.imports}）加载，
 * 一次性注册默认全局配置、Controller 治理主切面、异步动作精确切面、过滤器管道、
 * 限流器插件与后台管理接口。
 *
 * <h3>「注册 Bean 配置」与「yml 配置」二选一</h3>
 * <ul>
 *   <li>限流器：注册自定义 {@code @Bean RateLimiter} 即可完全替换（{@code @ConditionalOnMissingBean}）；</li>
 *   <li>自定义算法策略：注册 {@code @Bean RateLimitStrategy} 并配置 {@code algorithm=custom}；</li>
 *   <li>其余全局配置：通过 yml（{@code api.governance.*}）覆盖默认值。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy(proxyTargetClass = true)
@EnableConfigurationProperties(ApiGovernanceProperties.class)
@ConditionalOnProperty(prefix = "api.governance", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ApiGovernanceAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ApiGovernanceAutoConfiguration.class);

    // ==================== 内存指标与事件监听 ====================

    /**
     * 内存指标注册表（有界滑动窗口，保证内存不膨胀）。
     *
     * <p>创建时自动收集容器内所有 {@link MetricsEventListener} Bean（内置的 Micrometer
     * 桥接、告警分发器，以及用户自定义监听器），注册为事件监听器。
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricsRegistry metricsRegistry(ApiGovernanceProperties properties,
                                           ObjectProvider<MetricsEventListener> eventListeners) {
        ApiGovernanceProperties.Metrics m = properties.getMetrics();
        MetricsRegistry registry = new MetricsRegistry(m.getMaxApis(), m.getWindowSize(),
                m.getWindowSeconds() * 1000L);
        eventListeners.orderedStream().forEach(registry::addListener);
        return registry;
    }

    // ==================== Micrometer 指标桥接 ====================

    /**
     * Micrometer 指标桥接监听器：把治理事件同步为标准 Micrometer Counter / Timer，
     * 供 /actuator/prometheus 等标准生态采集。容器中没有 MeterRegistry 时本 Bean 为空转实现。
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.governance.metrics", name = "micrometer-enabled",
            havingValue = "true", matchIfMissing = true)
    public MicrometerMetricsEventListener micrometerMetricsEventListener(
            ObjectProvider<MeterRegistry> meterRegistry) {
        return new MicrometerMetricsEventListener(meterRegistry.getIfAvailable());
    }

    /**
     * 「统计 API 数量」Gauge：直接从内存注册表读取，随 /actuator/metrics 暴露。
     * 容器中没有 MeterRegistry 时不注册（返回 NullBean）。
     */
    @Bean(name = "apiGovernanceTrackedApisGauge")
    @ConditionalOnProperty(prefix = "api.governance.metrics", name = "micrometer-enabled",
            havingValue = "true", matchIfMissing = true)
    public Gauge apiGovernanceTrackedApisGauge(ObjectProvider<MeterRegistry> meterRegistry,
                                               MetricsRegistry metricsRegistry) {
        MeterRegistry registry = meterRegistry.getIfAvailable();
        if (registry == null) {
            return null;
        }
        return Gauge.builder(MicrometerMetricsEventListener.METRIC_APIS_TRACKED,
                        metricsRegistry, MetricsRegistry::size)
                .description("Number of APIs currently tracked by api-governance")
                .register(registry);
    }

    // ==================== 告警 ====================

    /**
     * 内置 Webhook 告警通知器：仅在显式启用且配置了 URL 时创建。
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.governance.alert.webhook", name = "enabled",
            havingValue = "true")
    public GovernanceAlertNotifier webhookAlertNotifier(ApiGovernanceProperties properties) {
        ApiGovernanceProperties.Webhook webhook = properties.getAlert().getWebhook();
        if (webhook.getUrl() == null || webhook.getUrl().trim().isEmpty()) {
            throw new IllegalStateException(
                    "api.governance.alert.webhook.enabled=true 但未配置 webhook.url，请补充目标地址");
        }
        log.info("启用 Webhook 告警通知器");
        return new WebhookAlertNotifier(webhook.getUrl().trim(), webhook.getTimeoutMs(),
                webhook.getSecretToken());
    }

    /**
     * 告警分发器：把指标事件转换为告警并分发给所有通知器（含告警风暴抑制）。
     * 告警关闭或无通知器时为空转实现（零开销）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.governance.alert", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AlertDispatcher alertDispatcher(ApiGovernanceProperties properties,
                                           ObjectProvider<GovernanceAlertNotifier> notifiers) {
        List<GovernanceAlertNotifier> notifierList = notifiers.orderedStream().toList();
        log.info("配置告警分发器 - 通知器数量: {}, 抑制窗口: {}ms",
                notifierList.size(), properties.getAlert().getSuppressIntervalMs());
        return new AlertDispatcher(notifierList, properties.getAlert().getSuppressIntervalMs(),
                properties.getLog().getSlowThresholdMs());
    }

    // ==================== 限流器插件 ====================

    /**
     * 本机限流器（默认）。
     *
     * <p>根据 {@code algorithm} 选择令牌桶/滑动窗口；当 {@code algorithm=custom} 时，
     * 包装用户注册的 {@link RateLimitStrategy} Bean。
     */
    @Bean
    @ConditionalOnMissingBean(RateLimiter.class)
    @ConditionalOnProperty(prefix = "api.governance.rate-limit", name = "type",
            havingValue = "local", matchIfMissing = true)
    public RateLimiter localRateLimiter(ApiGovernanceProperties properties,
                                        ObjectProvider<RateLimitStrategy> strategies) {
        RateLimitAlgorithm algorithm = RateLimitAlgorithm.fromCode(properties.getRateLimit().getAlgorithm());
        if (algorithm == RateLimitAlgorithm.CUSTOM) {
            RateLimitStrategy strategy = strategies.getIfAvailable();
            if (strategy == null) {
                throw new IllegalStateException(
                        "限流算法配置为 custom，但未找到 RateLimitStrategy Bean，请注册一个自定义策略");
            }
            log.info("配置限流器: 自定义算法策略 ({})", strategy.getClass().getSimpleName());
            return new StrategyRateLimiter("custom-strategy", strategy);
        }
        if (algorithm == RateLimitAlgorithm.SLIDING_WINDOW) {
            log.info("配置限流器: 本机滑动窗口");
            return new SlidingWindowRateLimiter();
        }
        log.info("配置限流器: 本机令牌桶");
        return new TokenBucketRateLimiter();
    }

    // ==================== 内置过滤器 ====================

    @Bean
    public MetadataCollectorFilter metadataCollectorFilter() {
        return new MetadataCollectorFilter();
    }

    @Bean
    public TrafficStatisticsFilter trafficStatisticsFilter(MetricsRegistry metricsRegistry) {
        return new TrafficStatisticsFilter(metricsRegistry);
    }

    /**
     * 限流键解析器（默认接口级限流；用户可注册自定义 Bean 切换为用户级等颗粒度）。
     */
    @Bean
    @ConditionalOnMissingBean
    public RateLimitKeyResolver rateLimitKeyResolver() {
        return new DefaultRateLimitKeyResolver();
    }

    @Bean
    public RateLimitFilter rateLimitFilter(ObjectProvider<RateLimiter> rateLimiter,
                                           MetricsRegistry metricsRegistry,
                                           ApiGovernanceProperties properties,
                                           RateLimitKeyResolver keyResolver,
                                           ObjectProvider<RateLimitRejectHandler> rejectHandlers) {
        return new RateLimitFilter(rateLimiter.getIfAvailable(), metricsRegistry, properties,
                keyResolver, rejectHandlers.getIfAvailable());
    }

    @Bean
    public SlowMethodFilter slowMethodFilter(MetricsRegistry metricsRegistry,
                                             ApiGovernanceProperties properties) {
        return new SlowMethodFilter(metricsRegistry, properties);
    }

    @Bean
    public LoggingFilter loggingFilter(ApiGovernanceProperties properties) {
        return new LoggingFilter(properties);
    }

    /**
     * 过滤器链：自动收集容器内所有 {@link PreFilter} / {@link PostFilter} Bean
     * （含内置与用户自定义），按 order 排序执行。
     */
    @Bean
    public FilterChain filterChain(List<PreFilter> preFilters, List<PostFilter> postFilters) {
        return new FilterChain(preFilters, postFilters);
    }

    // ==================== 切面与异常处理 ====================

    /**
     * Controller 治理管道的唯一 AOP 切面入口。
     */
    @Bean
    public GovernanceAspect governanceAspect(FilterChain filterChain,
                                             ApiGovernanceProperties properties) {
        return new GovernanceAspect(filterChain, properties);
    }

    /**
     * 治理拒绝异常的全局处理器。
     */
    @Bean
    @ConditionalOnMissingBean
    public GovernanceExceptionHandler governanceExceptionHandler() {
        return new GovernanceExceptionHandler();
    }

    // ==================== 方法生命周期异步插件 ====================

    /**
     * Default isolated and bounded executor. Registering an
     * {@link AsyncExecutorProvider} Bean replaces it completely.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "api.governance.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AsyncExecutorProvider asyncExecutorProvider(ApiGovernanceProperties properties) {
        return new DefaultAsyncExecutorProvider(properties.getAsync());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "api.governance.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AsyncHandlerExceptionHandler asyncHandlerExceptionHandler() {
        return new LoggingAsyncHandlerExceptionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "api.governance.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AsyncTaskRejectionHandler asyncTaskRejectionHandler() {
        return new LoggingAsyncTaskRejectionHandler();
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "api.governance.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AsyncTaskContextPropagator asyncTaskContextPropagator() {
        return new NoopAsyncTaskContextPropagator();
    }

    @Bean
    @ConditionalOnProperty(prefix = "api.governance.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AsyncHandlerRegistry asyncHandlerRegistry(ConfigurableListableBeanFactory beanFactory) {
        return new AsyncHandlerRegistry(beanFactory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "api.governance.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AsyncEventFactory asyncEventFactory(ObjectProvider<AsyncEventEnricher> enrichers) {
        return new AsyncEventFactory(enrichers.orderedStream().toList());
    }

    @Bean
    @ConditionalOnProperty(prefix = "api.governance.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AsyncDispatcher asyncDispatcher(AsyncHandlerRegistry registry,
                                           AsyncEventFactory eventFactory,
                                            AsyncExecutorProvider executorProvider,
                                            AsyncHandlerExceptionHandler exceptionHandler,
                                            AsyncTaskRejectionHandler rejectionHandler,
                                            AsyncTaskContextPropagator contextPropagator) {
        return new AsyncDispatcher(registry, eventFactory, executorProvider,
                exceptionHandler, rejectionHandler, contextPropagator);
    }

    @Bean
    @ConditionalOnProperty(prefix = "api.governance.async", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public AsyncActionAspect asyncActionAspect(AsyncDispatcher dispatcher) {
        return new AsyncActionAspect(dispatcher);
    }

    // ==================== 后台管理接口 ====================

    /**
     * 后台管理接口（供管理工具调用），默认启用。
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.governance.management", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public GovernanceManagementController governanceManagementController(
            ApiGovernanceProperties properties,
            ObjectProvider<RateLimiter> rateLimiter,
            FilterChain filterChain,
            MetricsRegistry metricsRegistry) {
        return new GovernanceManagementController(properties, rateLimiter.getIfAvailable(),
                filterChain, metricsRegistry);
    }

    /**
     * 管理接口轻量鉴权过滤器：仅当配置了 {@code management.auth-token} 时启用拦截；
     * 未配置令牌时注册但禁用（行为与未注册一致，保持向后兼容）。
     */
    @Bean
    @ConditionalOnProperty(prefix = "api.governance.management", name = "enabled",
            havingValue = "true", matchIfMissing = true)
    public FilterRegistrationBean<GovernanceManagementAuthFilter> governanceManagementAuthFilter(
            ApiGovernanceProperties properties) {
        ApiGovernanceProperties.Management management = properties.getManagement();
        GovernanceManagementAuthFilter filter = new GovernanceManagementAuthFilter(properties);
        FilterRegistrationBean<GovernanceManagementAuthFilter> registration = new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns(management.getBasePath() + "/*");
        registration.setEnabled(management.isAuthEnabled());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 200);
        if (management.isAuthEnabled()) {
            log.info("管理接口鉴权已启用 - header: {}", management.getAuthHeader());
        }
        return registration;
    }

    // ==================== Redis 限流（可选依赖） ====================

    /**
     * Redis 限流器装配：仅当 {@code StringRedisTemplate} 在类路径且配置 type=redis 时生效。
     * 「Redis 只封装」—— 内部实现仅为 Redis + Lua 脚本的封装，不掺杂业务逻辑。
     *
     * <p>统一包装 {@link FailSafeRateLimiter}：Redis 故障时按
     * {@code api.governance.rate-limit.fail-strategy}（open=放行 / close=拒绝）降级，
     * 并触发 {@code RATE_LIMITER_FAILURE} 告警（若告警已启用）。
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.data.redis.core.StringRedisTemplate")
    static class RedisRateLimitConfiguration {

        @Bean
        @ConditionalOnMissingBean(RateLimiter.class)
        @ConditionalOnProperty(prefix = "api.governance.rate-limit", name = "type",
                havingValue = "redis")
        public RateLimiter redisRateLimiter(ApiGovernanceProperties properties,
                                            StringRedisTemplate redisTemplate,
                                            ObjectProvider<AlertDispatcher> alertDispatcher) {
            RateLimitAlgorithm algorithm = RateLimitAlgorithm.fromCode(properties.getRateLimit().getAlgorithm());
            RateLimiter delegate;
            if (algorithm == RateLimitAlgorithm.SLIDING_WINDOW) {
                log.info("配置限流器: Redis 滑动窗口");
                delegate = new RedisSlidingWindowRateLimiter(redisTemplate);
            } else {
                log.info("配置限流器: Redis 令牌桶");
                delegate = new RedisTokenBucketRateLimiter(redisTemplate);
            }
            boolean failClose = "close".equals(properties.getRateLimit().getFailStrategy());
            return new FailSafeRateLimiter(delegate, failClose, alertDispatcher.getIfAvailable());
        }
    }
}
