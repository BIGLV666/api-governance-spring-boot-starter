package io.github.biglv666.apigovernance.ratelimit.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;

import java.io.IOException;
import java.util.List;

/**
 * 启动种子规则加载器（0.8.0 新增）。
 *
 * <p>从 {@code api.governance.rate-limit.dynamic-rules.seed-location} 指向的 YAML 文件
 * 读取规则并在启动时写入规则存储，用于「规则即代码」、灾备恢复与新环境一键铺开。
 *
 * <h3>文件格式</h3>
 * <pre>
 * rules:
 *   - pattern: com.x.OrderController#create
 *     limit: 100
 *     window: 60
 *     ttlSeconds: 300          # 可选，缺省取 default-ttl-seconds
 *     operator: ops
 *     reason: 大促临时收紧
 *     maxConcurrent: 10        # 可选，1.0.0 新增：并发上限
 * </pre>
 *
 * <p>使用 Spring Boot 自带的 YAML 解析器，不引入 Jackson 等额外依赖。
 *
 * @author API Governance Team
 * @since 0.8.0
 */
public final class RuleSeedLoader {

    private static final Logger log = LoggerFactory.getLogger(RuleSeedLoader.class);

    private RuleSeedLoader() {
    }

    /**
     * 从指定位置加载种子规则（不写入存储）。
     *
     * @param location      资源位置（classpath: / file:，可为空）
     * @param resourceLoader 资源加载器
     * @return 种子规则列表（位置为空时返回空列表）
     * @throws IllegalStateException 资源不存在或解析失败
     */
    public static List<SeedRule> load(String location, ResourceLoader resourceLoader) {
        if (location == null || location.isBlank()) {
            return List.of();
        }
        String trimmed = location.trim();
        Resource resource = resourceLoader.getResource(trimmed);
        if (!resource.exists()) {
            throw new IllegalStateException("动态规则种子文件不存在: " + trimmed);
        }
        try {
            List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                    .load("api-governance-rule-seed", resource);
            MutablePropertySources propertySources = new MutablePropertySources();
            sources.forEach(propertySources::addLast);
            Binder binder = new Binder(ConfigurationPropertySources.from(propertySources));
            return binder.bind("rules", Bindable.listOf(SeedRule.class)).orElse(List.of());
        } catch (IOException e) {
            throw new IllegalStateException("读取动态规则种子文件失败: " + trimmed
                    + " - " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("解析动态规则种子文件失败: " + trimmed
                    + " - " + e.getMessage(), e);
        }
    }

    /**
     * 把种子规则写入存储并记录审计。
     *
     * @param rules            种子规则
     * @param defaultWindow    缺省窗口（秒）
     * @param defaultTtlSeconds 缺省有效期（秒，0 = 永久）
     * @param store            规则存储
     * @param auditLog         审计日志（可为 null）
     * @param failFast         true 时任一规则失败立即抛异常；false 时记录 warn 继续
     * @return 成功写入条数
     */
    public static int apply(List<SeedRule> rules, int defaultWindow, long defaultTtlSeconds,
                            RateRuleStore store, RuleAuditLog auditLog, boolean failFast) {
        int applied = 0;
        for (SeedRule seed : rules) {
            try {
                DynamicRateRule rule = seed.toRule(defaultWindow, defaultTtlSeconds);
                store.put(rule);
                applied++;
                record(auditLog, rule, true, "seed applied");
            } catch (RuntimeException e) {
                record(seed, defaultWindow, defaultTtlSeconds, auditLog, e.getMessage());
                if (failFast) {
                    throw new IllegalStateException("加载动态规则种子失败 - pattern: "
                            + seed.getPattern() + " - " + e.getMessage(), e);
                }
                log.warn("跳过失败种子规则 - pattern: {}, 原因: {}", seed.getPattern(),
                        e.getMessage());
            }
        }
        return applied;
    }

    private static void record(RuleAuditLog auditLog, DynamicRateRule rule,
                               boolean success, String message) {
        if (auditLog == null) {
            return;
        }
        try {
            auditLog.record(new RuleAuditEntry(System.currentTimeMillis(),
                    RuleAuditEntry.Action.SEED, rule.getPattern(), null, null,
                    rule.getLimit(), rule.getWindow(), null, rule.getMaxConcurrent(),
                    rule.getExpiresAtMs(), rule.getOperator(), "", success, message));
        } catch (RuntimeException e) {
            log.warn("记录规则审计失败（不影响种子加载）: {}", e.getMessage());
        }
    }

    private static void record(SeedRule seed, int defaultWindow, long defaultTtlSeconds,
                               RuleAuditLog auditLog, String message) {
        if (auditLog == null) {
            return;
        }
        try {
            auditLog.record(new RuleAuditEntry(System.currentTimeMillis(),
                    RuleAuditEntry.Action.SEED, seed.getPattern(), null, null,
                    seed.getLimit(),
                    seed.getWindow() != null ? seed.getWindow() : defaultWindow,
                    null, seed.getMaxConcurrent(), 0L,
                    seed.getOperator() == null ? "seed" : seed.getOperator(),
                    "", false, message));
        } catch (RuntimeException e) {
            log.warn("记录规则审计失败（不影响种子加载）: {}", e.getMessage());
        }
    }

    /**
     * 种子规则 YAML 绑定模型。
     */
    public static class SeedRule {

        private String pattern;
        private Integer limit;
        private Integer window;
        private Long ttlSeconds;
        private String operator;
        private String reason;
        private Integer maxConcurrent;

        public String getPattern() {
            return pattern;
        }

        public void setPattern(String pattern) {
            this.pattern = pattern;
        }

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        public Integer getWindow() {
            return window;
        }

        public void setWindow(Integer window) {
            this.window = window;
        }

        public Long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(Long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }

        public String getOperator() {
            return operator;
        }

        public void setOperator(String operator) {
            this.operator = operator;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }

        public Integer getMaxConcurrent() {
            return maxConcurrent;
        }

        public void setMaxConcurrent(Integer maxConcurrent) {
            this.maxConcurrent = maxConcurrent;
        }

        DynamicRateRule toRule(int defaultWindow, long defaultTtlSeconds) {
            if (pattern == null || pattern.isBlank()) {
                throw new IllegalArgumentException("种子规则缺少 pattern");
            }
            if (limit == null) {
                throw new IllegalArgumentException("种子规则缺少 limit: " + pattern);
            }
            int effectiveWindow = window != null ? window : defaultWindow;
            long effectiveTtl = ttlSeconds != null ? ttlSeconds : defaultTtlSeconds;
            String effectiveOperator = operator == null || operator.isBlank()
                    ? "seed" : operator;
            return DynamicRateRule.of(pattern, limit, effectiveWindow,
                    effectiveTtl, effectiveOperator,
                    reason == null ? "seed" : reason, maxConcurrent);
        }
    }
}
