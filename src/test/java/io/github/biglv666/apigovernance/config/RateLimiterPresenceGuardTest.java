package io.github.biglv666.apigovernance.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 限流器装配完整性守卫测试：type=redis 但容器无任何 RateLimiter 时启动失败。
 *
 * <p>回归：Redis 依赖是可选的，宿主配置 {@code type=redis} 却未引入
 * spring-boot-starter-data-redis 时，Redis 配置类与本机限流器双双静默让位，
 * 此前所有 {@code @RateLimit} 仅剩一条 warn 日志即完全失效。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
class RateLimiterPresenceGuardTest {

    private final ApiGovernanceAutoConfiguration configuration = new ApiGovernanceAutoConfiguration();

    private final ConfigurableListableBeanFactory beanFactory = mock(ConfigurableListableBeanFactory.class);

    @Test
    void failsFastWhenRedisTypeHasNoRateLimiter() {
        when(beanFactory.getBeanNamesForType(any(Class.class))).thenReturn(new String[0]);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getRateLimit().setType("redis");

        SmartInitializingSingleton guard = configuration.rateLimiterPresenceGuard(properties, beanFactory);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("type=redis")
                .hasMessageContaining("spring-boot-starter-data-redis");
    }

    @Test
    void passesWhenAnyRateLimiterBeanExists() {
        when(beanFactory.getBeanNamesForType(any(Class.class))).thenReturn(new String[]{"localRateLimiter"});
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getRateLimit().setType("redis");

        SmartInitializingSingleton guard = configuration.rateLimiterPresenceGuard(properties, beanFactory);

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void passesWhenTypeIsLocalEvenWithoutLimiter() {
        when(beanFactory.getBeanNamesForType(any(Class.class))).thenReturn(new String[0]);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getRateLimit().setType("local");

        SmartInitializingSingleton guard = configuration.rateLimiterPresenceGuard(properties, beanFactory);

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void passesWhenRateLimitFilterDisabled() {
        when(beanFactory.getBeanNamesForType(any(Class.class))).thenReturn(new String[0]);
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getRateLimit().setType("redis");
        properties.getFilters().setRateLimit(false);

        SmartInitializingSingleton guard = configuration.rateLimiterPresenceGuard(properties, beanFactory);

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }
}
