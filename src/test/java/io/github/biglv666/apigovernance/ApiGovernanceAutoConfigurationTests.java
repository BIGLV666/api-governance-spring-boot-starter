package io.github.biglv666.apigovernance;

import io.github.biglv666.apigovernance.alert.internal.AlertDispatcher;
import io.github.biglv666.apigovernance.alert.webhook.WebhookAlertNotifier;
import io.github.biglv666.apigovernance.aspect.GovernanceAspect;
import io.github.biglv666.apigovernance.async.aspect.AsyncActionAspect;
import io.github.biglv666.apigovernance.async.internal.AsyncHandlerRegistry;
import io.github.biglv666.apigovernance.async.internal.NoopAsyncTaskContextPropagator;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskContextPropagator;
import io.github.biglv666.apigovernance.config.ApiGovernanceAutoConfiguration;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.filter.impl.LoggingFilter;
import io.github.biglv666.apigovernance.filter.impl.MetadataCollectorFilter;
import io.github.biglv666.apigovernance.filter.impl.RateLimitFilter;
import io.github.biglv666.apigovernance.filter.impl.SlowMethodFilter;
import io.github.biglv666.apigovernance.filter.impl.TrafficStatisticsFilter;
import io.github.biglv666.apigovernance.management.GovernanceManagementController;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.metrics.micrometer.MicrometerMetricsEventListener;
import io.github.biglv666.apigovernance.ratelimit.DefaultRateLimitKeyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimitKeyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.RateLimitStrategy;
import io.github.biglv666.apigovernance.ratelimit.StrategyRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.local.SlidingWindowRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.local.TokenBucketRateLimiter;
import io.github.biglv666.apigovernance.trace.ApiGovernanceTracingAutoConfiguration;
import io.micrometer.context.ContextSnapshotFactory;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动装配测试：验证「yml 配置」与「注册 Bean 配置」两条装配路径。
 *
 * @author API Governance Team
 * @since 1.0
 */
class ApiGovernanceAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiGovernanceAutoConfiguration.class));

    @Test
    void defaultContextLoads() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(MetricsRegistry.class);
            assertThat(ctx).hasSingleBean(FilterChain.class);
            assertThat(ctx).hasSingleBean(GovernanceAspect.class);
            assertThat(ctx).hasSingleBean(GovernanceManagementController.class);
            assertThat(ctx).hasSingleBean(RateLimiter.class);
            assertThat(ctx).hasSingleBean(AsyncActionAspect.class);
            assertThat(ctx).hasSingleBean(AsyncHandlerRegistry.class);
            assertThat(ctx).hasSingleBean(AsyncExecutorProvider.class);
            // 默认：本机令牌桶
            assertThat(ctx.getBean(RateLimiter.class)).isInstanceOf(TokenBucketRateLimiter.class);
        });
    }

    @Test
    void slidingWindowSelectedByYml() {
        runner.withPropertyValues("api.governance.rate-limit.algorithm=sliding-window")
                .run(ctx -> assertThat(ctx.getBean(RateLimiter.class))
                        .isInstanceOf(SlidingWindowRateLimiter.class));
    }

    @Test
    void customRateLimiterBeanOverridesDefault() {
        RateLimiter custom = new RateLimiter() {
            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                return false;
            }

            @Override
            public String getName() {
                return "custom-limiter";
            }
        };
        runner.withBean(RateLimiter.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(RateLimiter.class)).isSameAs(custom));
    }

    @Test
    void customStrategyWrappedWhenAlgorithmIsCustom() {
        runner.withPropertyValues("api.governance.rate-limit.algorithm=custom")
                .withBean(RateLimitStrategy.class, () -> (key, limit, window) -> true)
                .run(ctx -> assertThat(ctx.getBean(RateLimiter.class))
                        .isInstanceOf(StrategyRateLimiter.class));
    }

    @Test
    void disabledSkipsWholeConfiguration() {
        runner.withPropertyValues("api.governance.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(GovernanceAspect.class));
    }

    @Test
    void managementCanBeDisabled() {
        runner.withPropertyValues("api.governance.management.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(GovernanceManagementController.class));
    }

    @Test
    void asyncActionsCanBeDisabledIndependently() {
        runner.withPropertyValues("api.governance.async.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AsyncActionAspect.class);
                    assertThat(ctx).doesNotHaveBean(AsyncHandlerRegistry.class);
                    assertThat(ctx).doesNotHaveBean(AsyncExecutorProvider.class);
                    assertThat(ctx).hasSingleBean(GovernanceAspect.class);
                });
    }

    @Test
    void customExecutorProviderOverridesDefault() {
        AsyncExecutorProvider custom = () -> Runnable::run;
        runner.withBean(AsyncExecutorProvider.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(AsyncExecutorProvider.class)).isSameAs(custom));
    }

    @Test
    void governanceAspectWrapsAsyncActionAspect() {
        runner.run(ctx -> {
            GovernanceAspect governance = ctx.getBean(GovernanceAspect.class);
            AsyncActionAspect async = ctx.getBean(AsyncActionAspect.class);

            assertThat(AnnotationAwareOrderComparator.INSTANCE.compare(governance, async))
                    .isLessThan(0);
        });
    }

    @Test
    void defaultKeyResolverIsMethodLevel() {
        runner.run(ctx -> assertThat(ctx.getBean(RateLimitKeyResolver.class))
                .isInstanceOf(DefaultRateLimitKeyResolver.class));
    }

    @Test
    void customKeyResolverBeanOverridesDefault() {
        RateLimitKeyResolver custom = context -> "custom-key";
        runner.withBean(RateLimitKeyResolver.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(RateLimitKeyResolver.class)).isSameAs(custom));
    }

    // ==================== 0.2.0 新特性装配 ====================

    @Test
    void alertDispatcherPresentByDefaultAndListenersRegistered() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(AlertDispatcher.class);
            // 分发器是指标事件监听器，自动注册到内存指标注册表
            MetricsRegistry registry = ctx.getBean(MetricsRegistry.class);
            registry.recordReject("api", "GET", "/x", "reason");
            // 无通知器时为空转，不抛异常即通过
            assertThat(registry.size()).isEqualTo(1);
        });
    }

    @Test
    void alertCanBeDisabledIndependently() {
        runner.withPropertyValues("api.governance.alert.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(AlertDispatcher.class);
                    assertThat(ctx).hasSingleBean(GovernanceAspect.class);
                });
    }

    @Test
    void webhookNotifierRequiresUrl() {
        runner.withPropertyValues("api.governance.alert.webhook.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
        runner.withPropertyValues(
                        "api.governance.alert.webhook.enabled=true",
                        "api.governance.alert.webhook.url=https://example.com/hook")
                .run(ctx -> assertThat(ctx).hasSingleBean(WebhookAlertNotifier.class));
    }

    @Test
    void micrometerListenerBridgesEvents() {
        runner.withBean(SimpleMeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(MicrometerMetricsEventListener.class);
                    ctx.getBean(MetricsRegistry.class)
                            .recordResult("api", 100, true, false, "GET", "/x", null);
                    SimpleMeterRegistry meterRegistry = ctx.getBean(SimpleMeterRegistry.class);
                    assertThat(meterRegistry.counter("api.governance.requests",
                            "api", "api", "method", "GET", "outcome", "success").count()).isEqualTo(1.0);
                });
    }

    @Test
    void micrometerBridgeCanBeDisabled() {
        runner.withPropertyValues("api.governance.metrics.micrometer-enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MicrometerMetricsEventListener.class));
    }

    @Test
    void authFilterDisabledWithoutToken() {
        runner.run(ctx -> {
            FilterRegistrationBean<?> registration =
                    ctx.getBean("governanceManagementAuthFilter", FilterRegistrationBean.class);
            assertThat(registration.isEnabled()).isFalse();
        });
    }

    @Test
    void authFilterEnabledWithToken() {
        runner.withPropertyValues("api.governance.management.auth-token=secret")
                .run(ctx -> {
                    FilterRegistrationBean<?> registration =
                            ctx.getBean("governanceManagementAuthFilter", FilterRegistrationBean.class);
                    assertThat(registration.isEnabled()).isTrue();
                    assertThat(registration.getUrlPatterns()).contains("/api-governance/*");
                });
    }

    // ==================== 0.3.0 新特性装配 ====================

    @Test
    void builtInFiltersCanBeDisabledByProperties() {
        runner.withPropertyValues(
                        "api.governance.filters.metadata-collector=false",
                        "api.governance.filters.traffic-statistics=false",
                        "api.governance.filters.rate-limit=false",
                        "api.governance.filters.slow-method=false",
                        "api.governance.filters.logging=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(MetadataCollectorFilter.class);
                    assertThat(ctx).doesNotHaveBean(TrafficStatisticsFilter.class);
                    assertThat(ctx).doesNotHaveBean(RateLimitFilter.class);
                    assertThat(ctx).doesNotHaveBean(SlowMethodFilter.class);
                    assertThat(ctx).doesNotHaveBean(LoggingFilter.class);
                    // 全部内置过滤器关闭后，链为空但仍可用（用户自定义过滤器仍会加入）
                    assertThat(ctx.getBean(FilterChain.class).getPreFilterCount()).isZero();
                });
    }

    @Test
    void customFilterBeanOverridesBuiltIn() {
        LoggingFilter custom = new LoggingFilter(new io.github.biglv666.apigovernance.config.ApiGovernanceProperties());
        runner.withBean(LoggingFilter.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(LoggingFilter.class)).isSameAs(custom));
    }

    @Test
    void localRateLimiterRespectsMaxEntriesProperty() {
        runner.withPropertyValues("api.governance.rate-limit.max-entries=5")
                .run(ctx -> {
                    RateLimiter limiter = ctx.getBean(RateLimiter.class);
                    // maxEntries 影响 LRU 上限：行为上表现为所有键都能正常获取
                    assertThat(limiter.tryAcquire("k1", 10, 60)).isTrue();
                    assertThat(limiter.tryAcquire("k2", 10, 60)).isTrue();
                });
    }

    @Test
    void webhookPlatformWiring() {
        runner.withPropertyValues(
                        "api.governance.alert.webhook.enabled=true",
                        "api.governance.alert.webhook.url=https://example.com/hook",
                        "api.governance.alert.webhook.platform=dingtalk",
                        "api.governance.alert.webhook.sign-secret=secret")
                .run(ctx -> {
                    WebhookAlertNotifier notifier = ctx.getBean(WebhookAlertNotifier.class);
                    assertThat(notifier.getPlatform()).isEqualTo("dingtalk");
                });
    }

    @Test
    void webhookUnknownPlatformFailsStartup() {
        runner.withPropertyValues(
                        "api.governance.alert.webhook.enabled=true",
                        "api.governance.alert.webhook.url=https://example.com/hook",
                        "api.governance.alert.webhook.platform=slack")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    // ==================== 0.4.0 新特性装配 ====================

    @Test
    void governanceAspectBeanCanBeReplaced() {
        GovernanceAspect custom = new GovernanceAspect(
                new FilterChain(List.of(), List.of()), new io.github.biglv666.apigovernance.config.ApiGovernanceProperties());
        runner.withBean(GovernanceAspect.class, () -> custom)
                .run(ctx -> assertThat(ctx.getBean(GovernanceAspect.class)).isSameAs(custom));
    }

    @Test
    void governanceWorksWithoutTracingClasspath() {
        // 模拟 0.4.0 起未引入 micrometer-tracing/otlp 的宿主：
        // tracing 自动配置整体回退，异步传播退化为 Noop，治理核心能力全部可用
        runner.withClassLoader(new FilteredClassLoader(ContextSnapshotFactory.class))
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(GovernanceAspect.class);
                    assertThat(ctx).hasSingleBean(RateLimiter.class);
                    assertThat(ctx).hasSingleBean(FilterChain.class);
                    assertThat(ctx).hasSingleBean(GovernanceManagementController.class);
                    assertThat(ctx).doesNotHaveBean(ApiGovernanceTracingAutoConfiguration.class);
                    assertThat(ctx.getBean(AsyncTaskContextPropagator.class))
                            .isInstanceOf(NoopAsyncTaskContextPropagator.class);
                });
    }
}
