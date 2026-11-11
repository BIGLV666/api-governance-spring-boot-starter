package io.github.biglv666.apigovernance.health;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.ratelimit.FailSafeRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator 健康指示器与 Info 贡献器测试：UP/DOWN 判定、fail-open/fail-close 差异与信息暴露。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
class ApiGovernanceHealthIndicatorTest {

    /** 永远失败的限流器。 */
    static class BrokenRateLimiter implements RateLimiter {

        @Override
        public boolean tryAcquire(String key, int limit, int windowSeconds) {
            throw new IllegalStateException("connection refused");
        }

        @Override
        public String getName() {
            return "broken-redis";
        }
    }

    /** 可配置健康状态的规则存储替身。 */
    static class StubRuleStore implements RateRuleStore {

        long lastSuccess;
        long lastFailure;
        boolean healthy = true;

        @Override
        public Optional<DynamicRateRule> findMatch(String apiKey) {
            return Optional.empty();
        }

        @Override
        public Map<String, DynamicRateRule> all() {
            return Map.of();
        }

        @Override
        public void put(String pattern, int limit, int window) {
        }

        @Override
        public void remove(String pattern) {
        }

        @Override
        public String getName() {
            return "stub-redis";
        }

        @Override
        public boolean isHealthy() {
            return healthy;
        }

        @Override
        public long getLastSuccessfulRefreshMs() {
            return lastSuccess;
        }

        @Override
        public long getLastFailureMs() {
            return lastFailure;
        }
    }

    private static ApiGovernanceProperties properties(String failStrategy) {
        ApiGovernanceProperties properties = new ApiGovernanceProperties();
        properties.getRateLimit().setFailStrategy(failStrategy);
        return properties;
    }

    private static FailSafeRateLimiter failedLimiter(boolean failClose) {
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(new BrokenRateLimiter(), failClose, null);
        try {
            limiter.tryAcquire("k", 10, 1);
        } catch (RuntimeException expected) {
            // fail-close 会抛 RateLimiterUnavailableException，状态已记录
        }
        return limiter;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> details(Health health) {
        return (Map<String, Object>) health.getDetails().get("limiter");
    }

    @Test
    void upWhenNothingConfigured() {
        ApiGovernanceHealthIndicator indicator = new ApiGovernanceHealthIndicator(
                properties("open"), null, null, null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .containsEntry("enabled", true)
                .containsEntry("failStrategy", "open");
    }

    @Test
    void failOpenKeepsUpAfterLimiterFailureAndWarns() {
        ApiGovernanceHealthIndicator indicator = new ApiGovernanceHealthIndicator(
                properties("open"), failedLimiter(false), null, null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(details(health)).containsEntry("failureActive", true);
        assertThat((List<?>) health.getDetails().get("warnings"))
                .anyMatch(w -> w.toString().contains("fail-open"));
    }

    @Test
    void failCloseGoesDownAfterLimiterFailure() {
        ApiGovernanceHealthIndicator indicator = new ApiGovernanceHealthIndicator(
                properties("close"), failedLimiter(true), null, null);

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(details(health)).containsEntry("failureActive", true);
    }

    @Test
    void healthyLimiterKeepsUpEvenInFailClose() {
        FailSafeRateLimiter limiter = new FailSafeRateLimiter(new RateLimiter() {
            @Override
            public boolean tryAcquire(String key, int limit, int windowSeconds) {
                return true;
            }

            @Override
            public String getName() {
                return "healthy";
            }
        }, true, null);
        limiter.tryAcquire("k", 10, 1);

        Health health = new ApiGovernanceHealthIndicator(properties("close"), limiter, null, null)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("limiter"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("failureActive", false);
    }

    @Test
    void staleRuleStoreGoesDownInFailClose() {
        StubRuleStore store = new StubRuleStore();
        store.lastFailure = System.currentTimeMillis() - 60_000L;
        store.healthy = false;

        Health health = new ApiGovernanceHealthIndicator(properties("close"), null, store, null)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("ruleStore"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("stale", true)
                .containsEntry("healthy", false);
    }

    @Test
    void staleRuleStoreOnlyWarnsInFailOpen() {
        StubRuleStore store = new StubRuleStore();
        store.lastFailure = System.currentTimeMillis() - 60_000L;
        store.healthy = false;

        Health health = new ApiGovernanceHealthIndicator(properties("open"), null, store, null)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat((List<?>) health.getDetails().get("warnings"))
                .anyMatch(w -> w.toString().contains("stale"));
    }

    @Test
    void recentlyRefreshedStoreIsNotStale() {
        StubRuleStore store = new StubRuleStore();
        store.lastSuccess = System.currentTimeMillis();

        Health health = new ApiGovernanceHealthIndicator(properties("close"), null, store, null)
                .health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("ruleStore"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("stale", false);
    }

    @SuppressWarnings("unchecked")
    @Test
    void infoContributorExposesEffectiveConfiguration() {
        ApiGovernanceProperties properties = properties("open");
        properties.getRateLimit().setType("redis");
        properties.getRateLimit().setDefaultLimit(50);
        properties.getRateLimit().setDefaultWindow(2);
        InfoContributor contributor = new ApiGovernanceInfoContributor(properties);

        Info.Builder builder = new Info.Builder();
        contributor.contribute(builder);
        Map<String, Object> apiGovernance =
                (Map<String, Object>) builder.build().getDetails().get("apiGovernance");

        assertThat(apiGovernance).isNotNull();
        assertThat(apiGovernance).containsEntry("version", "development");
        assertThat(apiGovernance).containsEntry("enabled", true);
        Map<String, Object> rateLimit = (Map<String, Object>) apiGovernance.get("rateLimit");
        assertThat(rateLimit)
                .containsEntry("type", "redis")
                .containsEntry("defaultLimit", 50)
                .containsEntry("defaultWindow", 2)
                .containsEntry("failStrategy", "open");
        assertThat((Map<String, Object>) apiGovernance.get("alert")).isNotNull();
        assertThat((Map<String, Object>) apiGovernance.get("tracing")).isNotNull();
    }
}
