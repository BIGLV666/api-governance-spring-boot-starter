package org.example.apigovernancespringbootstarter;

import org.example.apigovernancespringbootstarter.aspect.GovernanceAspect;
import org.example.apigovernancespringbootstarter.config.ApiGovernanceAutoConfiguration;
import org.example.apigovernancespringbootstarter.filter.FilterChain;
import org.example.apigovernancespringbootstarter.management.GovernanceManagementController;
import org.example.apigovernancespringbootstarter.metrics.MetricsRegistry;
import org.example.apigovernancespringbootstarter.ratelimit.DefaultRateLimitKeyResolver;
import org.example.apigovernancespringbootstarter.ratelimit.RateLimitKeyResolver;
import org.example.apigovernancespringbootstarter.ratelimit.RateLimiter;
import org.example.apigovernancespringbootstarter.ratelimit.RateLimitStrategy;
import org.example.apigovernancespringbootstarter.ratelimit.StrategyRateLimiter;
import org.example.apigovernancespringbootstarter.ratelimit.local.SlidingWindowRateLimiter;
import org.example.apigovernancespringbootstarter.ratelimit.local.TokenBucketRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
}
