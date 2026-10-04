package io.github.biglv666.apigovernance;

import io.github.biglv666.apigovernance.alert.dedup.AlertDeduplicationGate;
import io.github.biglv666.apigovernance.alert.dedup.RedisAlertDeduplicationGate;
import io.github.biglv666.apigovernance.aspect.GovernanceAspect;
import io.github.biglv666.apigovernance.config.ApiGovernanceAutoConfiguration;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRateRuleStore;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import io.github.biglv666.apigovernance.ratelimit.rules.RedisRateRuleStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ContextConsumer;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 0.7.0 装配层测试：动态规则存储随 rate-limit.type 联动、开关关闭时不装配、
 * 集群告警去重闸门按需装配且独立于限流 type。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
class DynamicRulesAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiGovernanceAutoConfiguration.class));

    /** 断言切面拿到的规则存储与容器中的一一致（或都为 null）。 */
    private static final ContextConsumer<AssertableApplicationContext> ASSERT_ASPECT_WIRED = ctx -> {
        RateRuleStore store = ctx.getBeanProvider(RateRuleStore.class).getIfAvailable();
        GovernanceAspect aspect = ctx.getBean(GovernanceAspect.class);
        Field field = GovernanceAspect.class.getDeclaredField("rateRuleStore");
        field.setAccessible(true);
        assertThat(field.get(aspect)).isSameAs(store);
    };

    @Test
    void defaultLocalModeWiresInMemoryStore() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(RateRuleStore.class);
            assertThat(ctx.getBean(RateRuleStore.class)).isInstanceOf(InMemoryRateRuleStore.class);
        });
        runner.run(ASSERT_ASPECT_WIRED);
    }

    @Test
    void dynamicRulesDisabledWiresNoStoreAndAspectGetsNull() {
        runner.withPropertyValues("api.governance.rate-limit.dynamic-rules.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(RateRuleStore.class);
                    GovernanceAspect aspect = ctx.getBean(GovernanceAspect.class);
                    try {
                        Field field = GovernanceAspect.class.getDeclaredField("rateRuleStore");
                        field.setAccessible(true);
                        assertThat(field.get(aspect)).isNull();
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException(e);
                    }
                });
    }

    @Test
    void customStoreBeanWinsOverBuiltIn() {
        runner.withBean(CustomStore.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(RateRuleStore.class);
                    assertThat(ctx.getBean(RateRuleStore.class)).isInstanceOf(CustomStore.class);
                });
    }

    @Test
    void clusterDedupGateRequiresExplicitFlag() {
        // 默认关：无闸门
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(AlertDeduplicationGate.class));
        // 开关打开但容器中没有 StringRedisTemplate：启动失败（fail-fast，与 webhook 配置缺失同风格）
        runner.withPropertyValues("api.governance.alert.cluster-dedup-enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void clusterDedupGateWiredWhenRedisAvailable() {
        // 测试类路径自带 spring-data-redis；提供 mock 模板验证闸门装配，且独立于限流 type
        runner.withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .withPropertyValues(
                        "api.governance.rate-limit.type=local",
                        "api.governance.alert.cluster-dedup-enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AlertDeduplicationGate.class);
                    assertThat(ctx.getBean(AlertDeduplicationGate.class))
                            .isInstanceOf(RedisAlertDeduplicationGate.class);
                    // 限流 type=local 时规则存储仍是内存版（存储联动的是限流 type，不是告警开关）
                    assertThat(ctx.getBean(RateRuleStore.class))
                            .isInstanceOf(InMemoryRateRuleStore.class);
                });
    }

    @Test
    void redisRateLimitTypeWiresRedisRuleStore() {
        runner.withBean(StringRedisTemplate.class, () -> Mockito.mock(StringRedisTemplate.class))
                .withPropertyValues("api.governance.rate-limit.type=redis")
                .run(ctx -> {
                    RateLimiter limiter = ctx.getBean(RateLimiter.class);
                    assertThat(limiter.getName()).contains("redis");
                    assertThat(ctx.getBean(RateRuleStore.class)).isInstanceOf(RedisRateRuleStore.class);
                });
    }

    /** 自定义存储插件：验证「一切皆插件」在规则维度的落地。 */
    static class CustomStore implements RateRuleStore {
        @Override
        public java.util.Optional<io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule> findMatch(
                String apiKey) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.Map<String, io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule> all() {
            return java.util.Map.of();
        }

        @Override
        public void put(String pattern, int limit, int window) {
        }

        @Override
        public void remove(String pattern) {
        }

        @Override
        public String getName() {
            return "custom";
        }
    }
}
