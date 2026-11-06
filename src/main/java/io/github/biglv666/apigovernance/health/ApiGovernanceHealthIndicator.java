package io.github.biglv666.apigovernance.health;

import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.ratelimit.FailSafeRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * API 治理 Actuator 健康指示器（0.8.0 新增）。
 *
 * <p>把治理组件的运行状态暴露到 {@code /actuator/health}，供 K8s 探针与运维平台直接消费：
 * <ul>
 *   <li><b>限流器</b>：最近一次故障后是否尚未恢复；</li>
 *   <li><b>规则存储</b>：Redis 版本轮询是否超过 {@code refresh-interval-ms × stale-multiplier}
 *       未成功，或从未成功过；</li>
 *   <li><b>异步线程池</b>：是否已饱和（仅告警，不判 DOWN）。</li>
 * </ul>
 *
 * <h3>UP / DOWN 判定</h3>
 * <p>只有在 {@code fail-strategy=close}（配额优先）时，限流器故障或规则存储陈旧才判
 * {@code DOWN}；{@code fail-open}（默认，可用性优先）下返回 {@code UP} 并在
 * {@code warnings} 中提示，避免 Redis 短暂故障导致整个应用被探针重启。
 *
 * <p>本类只在类路径存在 actuator 时由自动配置装配，未引入 actuator 的宿主无任何影响。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
public class ApiGovernanceHealthIndicator implements HealthIndicator {

    private final ApiGovernanceProperties properties;
    private final RateLimiter rateLimiter;
    private final RateRuleStore ruleStore;
    private final AsyncExecutorProvider asyncExecutorProvider;

    /**
     * 构造健康指示器。
     *
     * @param properties            全局配置
     * @param rateLimiter           限流器（可为 null）
     * @param ruleStore             动态规则存储（可为 null）
     * @param asyncExecutorProvider 异步执行器提供者（可为 null）
     */
    public ApiGovernanceHealthIndicator(ApiGovernanceProperties properties,
                                        RateLimiter rateLimiter,
                                        RateRuleStore ruleStore,
                                        AsyncExecutorProvider asyncExecutorProvider) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.ruleStore = ruleStore;
        this.asyncExecutorProvider = asyncExecutorProvider;
    }

    @Override
    public Health health() {
        try {
            return buildHealth();
        } catch (RuntimeException e) {
            return Health.unknown()
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .build();
        }
    }

    private Health buildHealth() {
        long now = System.currentTimeMillis();
        boolean failClose = isFailClose();
        boolean down = false;
        List<String> warnings = new ArrayList<>();
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("enabled", properties.isEnabled());
        details.put("failStrategy", failClose ? "close" : "open");

        details.put("limiter", limiterDetails(failClose, warnings));
        if (Boolean.TRUE.equals(((Map<?, ?>) details.get("limiter")).get("failureActive"))
                && failClose) {
            down = true;
        }

        Map<String, Object> storeDetails = ruleStoreDetails(now, failClose, warnings);
        details.put("ruleStore", storeDetails);
        if (failClose && (Boolean.TRUE.equals(storeDetails.get("stale"))
                || Boolean.FALSE.equals(storeDetails.get("healthy")))) {
            down = true;
        }

        details.put("asyncExecutor", asyncExecutorDetails(warnings));
        details.put("warnings", warnings);

        return down
                ? Health.down().withDetails(details).build()
                : Health.up().withDetails(details).build();
    }

    private Map<String, Object> limiterDetails(boolean failClose, List<String> warnings) {
        Map<String, Object> limiter = new LinkedHashMap<>();
        limiter.put("present", rateLimiter != null);
        if (rateLimiter == null) {
            return limiter;
        }
        limiter.put("name", rateLimiter.getName());
        limiter.put("mode", failClose ? "fail-close" : "fail-open");
        if (rateLimiter instanceof FailSafeRateLimiter failSafe) {
            boolean failureActive = failSafe.isFailedSinceLastSuccess();
            limiter.put("lastSuccess", failSafe.getLastSuccessMs());
            limiter.put("lastFailure", failSafe.getLastFailureMs());
            limiter.put("failureActive", failureActive);
            if (failureActive) {
                warnings.add(failClose
                        ? "rate limiter failure active (fail-close)"
                        : "rate limiter failure active (fail-open, requests pass)");
            }
        } else {
            limiter.put("failureActive", false);
        }
        return limiter;
    }

    private Map<String, Object> ruleStoreDetails(long now, boolean failClose,
                                                 List<String> warnings) {
        Map<String, Object> store = new LinkedHashMap<>();
        store.put("present", ruleStore != null);
        if (ruleStore == null) {
            return store;
        }
        long lastSuccess = ruleStore.getLastSuccessfulRefreshMs();
        long lastFailure = ruleStore.getLastFailureMs();
        long threshold = staleThresholdMs();
        boolean stale = (lastSuccess == 0L && lastFailure > 0L)
                || (lastSuccess > 0L && now - lastSuccess > threshold);
        store.put("name", ruleStore.getName());
        store.put("healthy", ruleStore.isHealthy());
        store.put("lastSuccess", lastSuccess);
        store.put("lastFailure", lastFailure);
        store.put("stale", stale);
        store.put("staleThresholdMs", threshold);
        store.put("ruleCount", ruleStore.activeRules(now).size());
        if (stale) {
            warnings.add(failClose
                    ? "rate rule store stale (fail-close)"
                    : "rate rule store stale (fail-open, last snapshot kept)");
        } else if (!ruleStore.isHealthy()) {
            warnings.add(failClose
                    ? "rate rule store unhealthy (fail-close)"
                    : "rate rule store unhealthy (fail-open, last snapshot kept)");
        }
        return store;
    }

    private Map<String, Object> asyncExecutorDetails(List<String> warnings) {
        Map<String, Object> async = new LinkedHashMap<>();
        async.put("present", asyncExecutorProvider != null);
        if (asyncExecutorProvider == null) {
            return async;
        }
        Executor executor = asyncExecutorProvider.getExecutor();
        if (!(executor instanceof ThreadPoolExecutor pool)) {
            async.put("type", executor == null ? "none" : executor.getClass().getSimpleName());
            return async;
        }
        int remaining = pool.getQueue().remainingCapacity();
        boolean saturated = pool.getActiveCount() >= pool.getMaximumPoolSize()
                && remaining == 0;
        async.put("type", "thread-pool");
        async.put("poolSize", pool.getPoolSize());
        async.put("maximumPoolSize", pool.getMaximumPoolSize());
        async.put("activeCount", pool.getActiveCount());
        async.put("queueSize", pool.getQueue().size());
        async.put("queueRemainingCapacity", remaining);
        async.put("saturated", saturated);
        if (saturated) {
            warnings.add("async executor saturated (tasks may be rejected)");
        }
        return async;
    }

    private boolean isFailClose() {
        return "close".equalsIgnoreCase(properties.getRateLimit().getFailStrategy());
    }

    private long staleThresholdMs() {
        long refresh = properties.getRateLimit().getDynamicRules().getRefreshIntervalMs();
        int multiplier = properties.getManagement().getHealthStaleMultiplier();
        return Math.max(1000L, refresh) * multiplier;
    }
}
