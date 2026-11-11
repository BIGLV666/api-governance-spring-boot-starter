package io.github.biglv666.apigovernance.management;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.internal.AsyncHandlerRegistry;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.annotation.Skip;
import io.github.biglv666.apigovernance.concurrency.ConcurrencyPolicy;
import io.github.biglv666.apigovernance.concurrency.ConcurrencyPolicyResolver;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.Filter;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.filter.PostFilter;
import io.github.biglv666.apigovernance.filter.PreFilter;
import io.github.biglv666.apigovernance.metrics.ApiMetrics;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.metrics.RequestRecord;
import io.github.biglv666.apigovernance.ratelimit.RateLimitPolicy;
import io.github.biglv666.apigovernance.ratelimit.RateLimitPolicyResolver;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.local.SlidingWindowRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.local.TokenBucketRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.rules.DynamicRateRule;
import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRuleAuditLog;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import io.github.biglv666.apigovernance.ratelimit.rules.RuleAuditEntry;
import io.github.biglv666.apigovernance.ratelimit.rules.RuleAuditLog;
import io.github.biglv666.apigovernance.web.HttpRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * API 治理后台管理接口 —— 供管理工具/运维平台调用。
 *
 * <h3>接口清单</h3>
 * <ul>
 *   <li>{@code GET  /status}：治理系统状态</li>
 *   <li>{@code GET  /config}：当前配置</li>
 *   <li>{@code GET  /filters}：过滤器链信息</li>
 *   <li>{@code GET  /rate-limiter/status}：限流器状态</li>
 *   <li>{@code GET  /rate-limiter/count?key=}：指定 key 当前计数</li>
 *   <li>{@code POST /rate-limiter/reset?key=}：重置指定 key</li>
 *   <li>{@code POST /rate-limiter/reset-all}：重置全部限流</li>
 *   <li>{@code GET  /rate-limiter/rules}：动态限流规则列表（0.7.0 新增，0.8.0 增加 TTL/元数据）</li>
 *   <li>{@code PUT  /rate-limiter/rules}：新增/覆盖一条动态规则（0.7.0 新增，0.8.0 增加 TTL）</li>
 *   <li>{@code DELETE /rate-limiter/rules?pattern=}：删除一条动态规则（0.7.0 新增）</li>
 *   <li>{@code GET  /rate-limiter/rules/effective?apiKey=}：解释最终生效规则与优先级（0.8.0 新增）</li>
 *   <li>{@code GET  /rate-limiter/rules/audit?limit=}：规则变更审计（0.8.0 新增）</li>
 *   <li>{@code GET  /rate-limiter/rules/export}：导出规则（0.8.0 新增）</li>
 *   <li>{@code POST /rate-limiter/rules/import}：批量导入/替换（支持 dry-run，0.8.0 新增）</li>
 *   <li>{@code GET  /metrics}：全部 API 指标汇总</li>
 *   <li>{@code GET  /metrics/detail?key=}：单 API 指标明细（含最近记录）</li>
 *   <li>{@code GET  /metrics/slow?key=}：单 API 慢方法列表</li>
 *   <li>{@code DELETE /metrics}：清空全部指标</li>
 *   <li>{@code DELETE /metrics?key=}：清空指定 API 指标</li>
 * </ul>
 *
 * <p>注意：管理接口自身被 {@link Skip} 标记，不参与治理（不限流、不统计、不记日志）。
 * 生产环境请务必通过安全组件（网关鉴权/IP 白名单等）保护本组接口。
 *
 * @author API Governance Team
 * @since 1.0
 */
@Skip(reason = "管理接口自身不参与治理")
@RestController
@RequestMapping("${api.governance.management.base-path:/api-governance}")
public class GovernanceManagementController {

    private static final Logger log = LoggerFactory.getLogger(GovernanceManagementController.class);

    private final ApiGovernanceProperties properties;
    private final RateLimiter rateLimiter;
    private final FilterChain filterChain;
    private final MetricsRegistry metricsRegistry;
    private final AsyncHandlerRegistry asyncHandlerRegistry;
    private final AsyncExecutorProvider asyncExecutorProvider;
    /** 动态限流规则存储（0.7.0 新增，可能为 null：动态规则关闭时）。 */
    private final RateRuleStore rateRuleStore;

    /** 动态规则变更审计（0.8.0 新增，永不为 null；容量 0 时为空转实现）。 */
    private final RuleAuditLog auditLog;

    /**
     * 构造管理控制器（0.8.0 起主构造器，含动态规则审计）。
     *
     * @param properties            全局配置
     * @param rateLimiter           限流器（可能为 null）
     * @param filterChain           过滤器链
     * @param metricsRegistry       指标注册表
     * @param asyncHandlerRegistry  异步 Handler 注册表（可能为 null：异步插件关闭时）
     * @param asyncExecutorProvider 异步执行器（可能为 null：异步插件关闭时）
     * @param rateRuleStore         动态限流规则存储（可能为 null：动态规则关闭时）
     * @param auditLog              规则变更审计（可能为 null：按空转处理）
     */
    public GovernanceManagementController(ApiGovernanceProperties properties,
                                          RateLimiter rateLimiter,
                                          FilterChain filterChain,
                                          MetricsRegistry metricsRegistry,
                                          AsyncHandlerRegistry asyncHandlerRegistry,
                                          AsyncExecutorProvider asyncExecutorProvider,
                                          RateRuleStore rateRuleStore,
                                          RuleAuditLog auditLog) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.filterChain = filterChain;
        this.metricsRegistry = metricsRegistry;
        this.asyncHandlerRegistry = asyncHandlerRegistry;
        this.asyncExecutorProvider = asyncExecutorProvider;
        this.rateRuleStore = rateRuleStore;
        this.auditLog = auditLog == null ? new InMemoryRuleAuditLog(0) : auditLog;
    }

    /**
     * 构造管理控制器（0.7.x 兼容重载）。
     *
     * <p>规则变更审计按「关闭」处理。
     *
     * @param properties            全局配置
     * @param rateLimiter           限流器（可能为 null）
     * @param filterChain           过滤器链
     * @param metricsRegistry       指标注册表
     * @param asyncHandlerRegistry  异步 Handler 注册表（可能为 null）
     * @param asyncExecutorProvider 异步执行器（可能为 null）
     * @param rateRuleStore         动态限流规则存储（可能为 null）
     * @deprecated 0.8.0 起新增规则审计，请改用八参构造器
     */
    @Deprecated
    public GovernanceManagementController(ApiGovernanceProperties properties,
                                          RateLimiter rateLimiter,
                                          FilterChain filterChain,
                                          MetricsRegistry metricsRegistry,
                                          AsyncHandlerRegistry asyncHandlerRegistry,
                                          AsyncExecutorProvider asyncExecutorProvider,
                                          RateRuleStore rateRuleStore) {
        this(properties, rateLimiter, filterChain, metricsRegistry,
                asyncHandlerRegistry, asyncExecutorProvider, rateRuleStore, null);
    }

    /**
     * 构造管理控制器（0.6.x 兼容重载）。
     *
     * <p>动态规则端点与审计按「未启用」处理。
     *
     * @param properties            全局配置
     * @param rateLimiter           限流器（可能为 null）
     * @param filterChain           过滤器链
     * @param metricsRegistry       指标注册表
     * @param asyncHandlerRegistry  异步 Handler 注册表（可能为 null）
     * @param asyncExecutorProvider 异步执行器（可能为 null）
     * @deprecated 0.7.0 起新增动态规则端点，请改用七参/八参构造器
     */
    @Deprecated
    public GovernanceManagementController(ApiGovernanceProperties properties,
                                          RateLimiter rateLimiter,
                                          FilterChain filterChain,
                                          MetricsRegistry metricsRegistry,
                                          AsyncHandlerRegistry asyncHandlerRegistry,
                                          AsyncExecutorProvider asyncExecutorProvider) {
        this(properties, rateLimiter, filterChain, metricsRegistry,
                asyncHandlerRegistry, asyncExecutorProvider, null, null);
    }

    /**
     * 构造管理控制器（0.4.x 兼容重载）。
     *
     * <p>异步观测相关端点按「异步插件关闭」处理。
     *
     * @param properties      全局配置
     * @param rateLimiter     限流器（可能为 null）
     * @param filterChain     过滤器链
     * @param metricsRegistry 指标注册表
     * @deprecated 0.5.0 起新增异步观测端点，请改用六参构造器
     */
    @Deprecated
    public GovernanceManagementController(ApiGovernanceProperties properties,
                                          RateLimiter rateLimiter,
                                          FilterChain filterChain,
                                          MetricsRegistry metricsRegistry) {
        this(properties, rateLimiter, filterChain, metricsRegistry, null, null);
    }

    /**
     * 治理系统状态。
     */
    @GetMapping("/status")
    public Map<String, Object> status() {
        // 版本号取自 jar manifest（maven-jar-plugin 生成），开发环境类路径下可能为 null
        String version = GovernanceManagementController.class.getPackage().getImplementationVersion();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", properties.isEnabled());
        body.put("version", version != null ? version : "unknown");
        body.put("rateLimiter", rateLimiter != null ? rateLimiter.getName() : "none");
        body.put("preFilterCount", filterChain.getPreFilterCount());
        body.put("postFilterCount", filterChain.getPostFilterCount());
        body.put("trackedApis", metricsRegistry.size());
        return body;
    }

    /**
     * 当前配置（敏感字段已掩码）。
     *
     * <p>{@code management.auth-token}、{@code alert.webhook.secret-token} 与
     * {@code alert.webhook.sign-secret} 非空时以 {@value #MASK} 返回；
     * {@code alert.webhook.url} 的 query 参数（可能携带机器人 access_token）同样掩码，
     * 仅保留协议、主机与路径；其余配置项原样输出。
     */
    @GetMapping("/config")
    public ApiGovernanceProperties config() {
        return maskedConfig();
    }

    /**
     * 过滤器链信息。
     */
    @GetMapping("/filters")
    public Map<String, Object> filters() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pre", describe(filterChain.getPreFilters()));
        body.put("post", describe(filterChain.getPostFilters()));
        return body;
    }

    /**
     * 限流器状态。
     */
    @GetMapping("/rate-limiter/status")
    public RateLimiterStatus rateLimiterStatus() {
        RateLimiterStatus status = new RateLimiterStatus();
        if (rateLimiter != null) {
            status.setEnabled(true);
            status.setName(rateLimiter.getName());
            status.setType(properties.getRateLimit().getType());
            status.setAlgorithm(properties.getRateLimit().getAlgorithm());
            if (rateLimiter instanceof TokenBucketRateLimiter) {
                status.setManagedKeys(((TokenBucketRateLimiter) rateLimiter).getBucketCount());
            } else if (rateLimiter instanceof SlidingWindowRateLimiter) {
                status.setManagedKeys(((SlidingWindowRateLimiter) rateLimiter).getWindowCount());
            }
        } else {
            status.setEnabled(false);
            status.setName("none");
        }
        return status;
    }

    /**
     * 指定 key 的当前限流计数。
     *
     * @param key 限流键（API 唯一标识）
     */
    @GetMapping("/rate-limiter/count")
    public Map<String, Object> rateLimitCount(@RequestParam("key") String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        if (rateLimiter == null) {
            body.put("enabled", false);
            body.put("count", -1);
            return body;
        }
        body.put("enabled", true);
        body.put("count", rateLimiter.getCurrentCount(key));
        body.put("rateLimiter", rateLimiter.getName());
        return body;
    }

    /**
     * 重置指定 key 的限流状态。
     *
     * @param key 限流键
     */
    @PostMapping("/rate-limiter/reset")
    public Map<String, Object> resetRateLimit(@RequestParam("key") String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (isMutationsDisabled()) {
            return mutationsDisabledBody();
        }
        if (rateLimiter == null) {
            body.put("success", false);
            body.put("message", "限流器未配置");
            return body;
        }
        try {
            rateLimiter.reset(key);
            body.put("success", true);
            body.put("message", "重置成功");
            body.put("key", key);
            log.info("重置限流状态 - key: {}", key);
        } catch (Exception e) {
            body.put("success", false);
            body.put("message", "重置失败: " + e.getMessage());
            log.error("重置限流失败 - key: {}", key, e);
        }
        return body;
    }

    /**
     * 重置全部限流状态。
     */
    @PostMapping("/rate-limiter/reset-all")
    public Map<String, Object> resetAllRateLimit() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (isMutationsDisabled()) {
            return mutationsDisabledBody();
        }
        if (rateLimiter == null) {
            body.put("success", false);
            body.put("message", "限流器未配置");
            return body;
        }
        try {
            rateLimiter.resetAll();
            body.put("success", true);
            body.put("message", "重置全部限流状态成功");
            log.warn("重置全部限流状态");
        } catch (Exception e) {
            body.put("success", false);
            body.put("message", "重置失败: " + e.getMessage());
            log.error("重置全部限流失败", e);
        }
        return body;
    }

    // ==================== 动态限流规则（0.7.0 新增） ====================

    /**
     * 动态限流规则列表（pattern、limit、window、TTL、操作元数据与存储类型）。
     *
     * <p>只返回未过期规则；{@code ttlSecondsRemaining = -1} 表示永久有效。
     */
    @GetMapping("/rate-limiter/rules")
    public Map<String, Object> rateLimitRules() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (rateRuleStore == null) {
            body.put("success", false);
            body.put("message", "动态规则未启用"
                    + " (api.governance.rate-limit.dynamic-rules.enabled=false)");
            return body;
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rules = new ArrayList<>();
        for (DynamicRateRule rule : rateRuleStore.activeRules(now).values()) {
            rules.add(ruleToMap(rule, now));
        }
        body.put("success", true);
        body.put("store", rateRuleStore.getName());
        body.put("count", rules.size());
        body.put("rules", rules);
        return body;
    }

    /**
     * 解释指定 API 的最终生效限流策略与优先级来源（0.8.0 新增）。
     *
     * <p>返回注解 / 全局默认 / 动态规则各自的值，以及最终生效值与来源。
     * 类或方法无法反查时仍返回全局默认 + 动态规则解释，并在 {@code message} 中说明。
     */
    @GetMapping("/rate-limiter/rules/effective")
    public Map<String, Object> effectiveRateLimitRule(@RequestParam("apiKey") String apiKey) {
        Map<String, Object> body = new LinkedHashMap<>();
        RateLimitPolicyResolver.ApiKeyResolution resolution =
                RateLimitPolicyResolver.resolveForApiKey(apiKey, properties, rateRuleStore);
        RateLimitPolicy policy = resolution.policy();

        Map<String, Object> annotation = new LinkedHashMap<>();
        annotation.put("limit", policy.getAnnotationLimit());
        annotation.put("window", policy.getAnnotationWindow());
        annotation.put("enabled", policy.isAnnotationEnabled());
        annotation.put("source", policy.getAnnotationSource().getCode());

        Map<String, Object> globalDefault = new LinkedHashMap<>();
        globalDefault.put("limit", properties.getRateLimit().getDefaultLimit());
        globalDefault.put("window", properties.getRateLimit().getDefaultWindow());

        Map<String, Object> effective = new LinkedHashMap<>();
        effective.put("limit", policy.getLimit());
        effective.put("window", policy.getWindow());
        effective.put("enabled", policy.isEnabled());
        effective.put("source", policy.getSource().getCode());

        body.put("success", true);
        body.put("apiKey", apiKey);
        body.put("classResolved", resolution.classResolved());
        body.put("methodResolved", resolution.methodResolved());
        body.put("ambiguous", resolution.ambiguous());
        body.put("message", resolution.message());
        body.put("annotation", annotation);
        body.put("globalDefault", globalDefault);
        body.put("dynamicRule", policy.getDynamicRule() == null
                ? null : ruleToMap(policy.getDynamicRule(), System.currentTimeMillis()));
        body.put("effective", effective);

        ConcurrencyPolicy concurrency = ConcurrencyPolicyResolver.resolve(
                resolution.targetClass(), resolution.method(), properties, policy.getDynamicRule());
        Map<String, Object> concurrencyBody = new LinkedHashMap<>();
        concurrencyBody.put("annotationPermits", concurrency.getAnnotationPermits());
        concurrencyBody.put("permits", concurrency.getPermits());
        concurrencyBody.put("enabled", concurrency.isEnabled());
        concurrencyBody.put("source", concurrency.getSource().getCode());
        concurrencyBody.put("globalDefault", properties.getConcurrencyLimit().getDefaultPermits());
        body.put("concurrency", concurrencyBody);

        body.put("keyExpression", policy.getKeyExpression());
        body.put("store", rateRuleStore == null ? null : rateRuleStore.getName());
        return body;
    }

    /**
     * 规则变更审计（0.8.0 新增）：时间倒序，最新在前。
     */
    @GetMapping("/rate-limiter/rules/audit")
    public Map<String, Object> rateLimitRuleAudit(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        int effectiveLimit = Math.max(1, Math.min(limit, 1000));
        List<Map<String, Object>> entries = new ArrayList<>();
        for (RuleAuditEntry entry : auditLog.recent(effectiveLimit)) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("timestamp", entry.timestampMs());
            item.put("action", entry.action().name());
            item.put("pattern", entry.pattern());
            item.put("beforeLimit", entry.beforeLimit());
            item.put("beforeWindow", entry.beforeWindow());
            item.put("afterLimit", entry.afterLimit());
            item.put("afterWindow", entry.afterWindow());
            item.put("beforeMaxConcurrent", entry.beforeMaxConcurrent());
            item.put("afterMaxConcurrent", entry.afterMaxConcurrent());
            item.put("expiresAt", entry.expiresAtMs());
            item.put("operator", entry.operator());
            item.put("clientIp", entry.clientIp());
            item.put("success", entry.success());
            item.put("message", entry.message());
            entries.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("log", auditLog.getName());
        body.put("capacity", auditLog.getCapacity());
        body.put("count", entries.size());
        body.put("entries", entries);
        return body;
    }

    /**
     * 导出全部未过期规则（0.8.0 新增）：可保存为种子文件或用于灾备恢复。
     */
    @GetMapping("/rate-limiter/rules/export")
    public Map<String, Object> exportRateLimitRules() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (rateRuleStore == null) {
            body.put("success", false);
            body.put("message", "动态规则未启用"
                    + " (api.governance.rate-limit.dynamic-rules.enabled=false)");
            return body;
        }
        long now = System.currentTimeMillis();
        List<Map<String, Object>> rules = new ArrayList<>();
        for (DynamicRateRule rule : rateRuleStore.activeRules(now).values()) {
            rules.add(ruleToMap(rule, now));
        }
        body.put("success", true);
        body.put("format", "api-governance-rules-v1");
        body.put("store", rateRuleStore.getName());
        body.put("exportedAt", now);
        body.put("count", rules.size());
        body.put("rules", rules);
        return body;
    }

    /**
     * 批量导入规则（0.8.0 新增）。
     *
     * <p>请求体：{@code {"rules": [...], "mode": "merge|replace", "dryRun": true}}。
     * 先整体校验（任一规则非法则全部不写），{@code dryRun=true} 时只返回将要变更的计数。
     */
    @PostMapping("/rate-limiter/rules/import")
    public Map<String, Object> importRateLimitRules(
            @RequestBody(required = false) Map<String, Object> requestBody) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (isMutationsDisabled()) {
            return mutationsDisabledBody();
        }
        if (rateRuleStore == null) {
            body.put("success", false);
            body.put("message", "动态规则未启用"
                    + " (api.governance.rate-limit.dynamic-rules.enabled=false)");
            return body;
        }
        if (requestBody == null || !(requestBody.get("rules") instanceof List<?> rawRules)) {
            body.put("success", false);
            body.put("message", "缺少必填字段 rules（规则数组）");
            return body;
        }
        String mode = parseStringField(requestBody.get("mode"));
        mode = mode == null ? "merge" : mode.toLowerCase();
        if (!"merge".equals(mode) && !"replace".equals(mode)) {
            body.put("success", false);
            body.put("message", "mode 仅支持 merge 或 replace，收到: " + mode);
            return body;
        }
        boolean dryRun = parseBooleanField(requestBody.get("dryRun"));

        List<DynamicRateRule> incoming = new ArrayList<>();
        List<Map<String, Object>> errors = new ArrayList<>();
        int index = 0;
        for (Object item : rawRules) {
            try {
                if (!(item instanceof Map<?, ?> map)) {
                    throw new IllegalArgumentException("规则必须是 JSON 对象");
                }
                incoming.add(parseRule(map));
            } catch (RuntimeException e) {
                Map<String, Object> error = new LinkedHashMap<>();
                error.put("index", index);
                error.put("message", e.getMessage());
                errors.add(error);
            }
            index++;
        }
        if (!errors.isEmpty()) {
            body.put("success", false);
            body.put("message", "存在 " + errors.size() + " 条非法规则，未写入任何规则");
            body.put("errors", errors);
            return body;
        }

        Map<String, DynamicRateRule> unique = new LinkedHashMap<>();
        for (DynamicRateRule rule : incoming) {
            unique.put(rule.getPattern(), rule);
        }
        long now = System.currentTimeMillis();
        Map<String, DynamicRateRule> existing = rateRuleStore.activeRules(now);
        int maxRules = properties.getRateLimit().getDynamicRules().getMaxRules();
        Set<String> targetPatterns = "replace".equals(mode)
                ? new LinkedHashSet<>(unique.keySet())
                : new LinkedHashSet<>(existing.keySet());
        targetPatterns.addAll(unique.keySet());
        if (targetPatterns.size() > maxRules) {
            body.put("success", false);
            body.put("message", "导入后将达到 " + targetPatterns.size()
                    + " 条规则，超过上限 " + maxRules);
            return body;
        }

        int created = 0;
        int updated = 0;
        for (String pattern : unique.keySet()) {
            if (existing.containsKey(pattern)) {
                updated++;
            } else {
                created++;
            }
        }
        int deleted = 0;
        if ("replace".equals(mode)) {
            for (String pattern : existing.keySet()) {
                if (!unique.containsKey(pattern)) {
                    deleted++;
                }
            }
        }

        body.put("success", true);
        body.put("dryRun", dryRun);
        body.put("mode", mode);
        body.put("total", unique.size());
        body.put("created", created);
        body.put("updated", updated);
        body.put("deleted", deleted);
        if (dryRun) {
            body.put("message", "dry-run 通过，未写入任何规则");
            return body;
        }

        RuleAuditEntry.Action action = "replace".equals(mode)
                ? RuleAuditEntry.Action.REPLACE : RuleAuditEntry.Action.IMPORT;
        try {
            if ("replace".equals(mode)) {
                rateRuleStore.replaceAll(unique.values());
                for (String pattern : existing.keySet()) {
                    if (!unique.containsKey(pattern)) {
                        recordAudit(action, pattern, existing.get(pattern), null,
                                currentOperator(), true, "removed by import replace");
                    }
                }
            } else {
                rateRuleStore.putAll(unique.values());
            }
        } catch (RuntimeException e) {
            for (DynamicRateRule rule : unique.values()) {
                recordAudit(action, rule.getPattern(), existing.get(rule.getPattern()), rule,
                        currentOperator(), false, e.getMessage());
            }
            body.put("success", false);
            body.put("message", "导入失败: " + e.getMessage());
            return body;
        }
        for (DynamicRateRule rule : unique.values()) {
            recordAudit(action, rule.getPattern(), existing.get(rule.getPattern()), rule,
                    currentOperator(), true, "import applied");
        }
        body.put("message", "规则导入成功");
        log.warn("批量导入动态限流规则 - mode: {}, total: {}, created: {}, updated: {}, deleted: {}, store: {}",
                mode, unique.size(), created, updated, deleted, rateRuleStore.getName());
        return body;
    }

    /**
     * 新增/覆盖一条动态规则（同 pattern 覆盖，立即生效，无需重启/重新发布）。
     *
     * <p>请求体 JSON：{@code {"pattern": "com.x.UserController#get", "limit": 100,
     * "window": 60}}。{@code limit}: -1=不限流（可覆盖接口上的 @RateLimit）/
     * 0=封禁 / &gt;0=窗口内上限；{@code window} 缺省取全局 default-window。
     */
    @PutMapping("/rate-limiter/rules")
    public Map<String, Object> putRateLimitRule(
            @RequestBody(required = false) Map<String, Object> requestBody) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (isMutationsDisabled()) {
            return mutationsDisabledBody();
        }
        if (rateRuleStore == null) {
            body.put("success", false);
            body.put("message", "动态规则未启用"
                    + " (api.governance.rate-limit.dynamic-rules.enabled=false)");
            return body;
        }
        String pattern = requestBody != null
                && requestBody.get("pattern") instanceof String s ? s.trim() : null;
        if (pattern == null || pattern.isEmpty()) {
            body.put("success", false);
            body.put("message", "缺少必填字段 pattern（字符串，支持尾部 * 通配）");
            return body;
        }
        Integer limit = parseIntField(requestBody.get("limit"));
        if (limit == null) {
            body.put("success", false);
            body.put("message", "缺少或非法字段 limit（-1=不限流 / 0=封禁 / >0=窗口内上限）");
            return body;
        }
        Integer window = parseIntField(requestBody.get("window"));
        if (window == null) {
            window = properties.getRateLimit().getDefaultWindow();
        }
        Long rawTtlSeconds = parseLongField(requestBody.get("ttlSeconds"));
        if (rawTtlSeconds != null && rawTtlSeconds < 0) {
            body.put("success", false);
            body.put("message", "ttlSeconds 不能为负数（0 或缺省表示永久有效）");
            return body;
        }
        long ttlSeconds = rawTtlSeconds != null
                ? rawTtlSeconds
                : properties.getRateLimit().getDynamicRules().getDefaultTtlSeconds();
        String operator = firstNonBlank(parseStringField(requestBody.get("operator")),
                currentOperator());
        String reason = parseStringField(requestBody.get("reason"));
        Integer maxConcurrent = parseIntField(requestBody.get("maxConcurrent"));

        DynamicRateRule rule;
        try {
            rule = DynamicRateRule.of(pattern, limit, window, ttlSeconds, operator, reason,
                    maxConcurrent);
        } catch (IllegalArgumentException e) {
            recordAudit(RuleAuditEntry.Action.PUT, pattern, null, null, operator,
                    false, e.getMessage());
            body.put("success", false);
            body.put("message", e.getMessage());
            return body;
        }
        DynamicRateRule before = rateRuleStore.activeRules(System.currentTimeMillis())
                .get(rule.getPattern());
        try {
            rateRuleStore.put(rule);
        } catch (IllegalArgumentException | IllegalStateException e) {
            recordAudit(RuleAuditEntry.Action.PUT, pattern, before, rule, operator,
                    false, e.getMessage());
            body.put("success", false);
            body.put("message", e.getMessage());
            return body;
        }
        recordAudit(RuleAuditEntry.Action.PUT, rule.getPattern(), before, rule, operator,
                true, "rule applied");
        body.put("success", true);
        body.put("message", "规则已生效");
        body.put("pattern", rule.getPattern());
        body.put("limit", rule.getLimit());
        body.put("window", rule.getWindow());
        body.put("expiresAt", rule.getExpiresAtMs());
        body.put("ttlSecondsRemaining", rule.getTtlSecondsRemaining(System.currentTimeMillis()));
        body.put("operator", rule.getOperator());
        body.put("reason", rule.getReason());
        body.put("maxConcurrent", rule.getMaxConcurrent());
        body.put("store", rateRuleStore.getName());
        log.warn("提交动态限流规则 - pattern: {}, limit: {}, window: {}s, ttl: {}s, operator: {}, store: {}",
                rule.getPattern(), rule.getLimit(), rule.getWindow(), ttlSeconds,
                rule.getOperator(), rateRuleStore.getName());
        return body;
    }

    /**
     * 删除一条动态规则（幂等：pattern 不存在同样返回成功）。
     */
    @DeleteMapping("/rate-limiter/rules")
    public Map<String, Object> deleteRateLimitRule(@RequestParam("pattern") String pattern) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (isMutationsDisabled()) {
            return mutationsDisabledBody();
        }
        if (rateRuleStore == null) {
            body.put("success", false);
            body.put("message", "动态规则未启用"
                    + " (api.governance.rate-limit.dynamic-rules.enabled=false)");
            return body;
        }
        String operator = currentOperator();
        DynamicRateRule before = rateRuleStore.activeRules(System.currentTimeMillis()).get(pattern);
        try {
            rateRuleStore.remove(pattern);
        } catch (IllegalStateException e) {
            recordAudit(RuleAuditEntry.Action.DELETE, pattern, before, null, operator,
                    false, e.getMessage());
            body.put("success", false);
            body.put("message", e.getMessage());
            return body;
        }
        recordAudit(RuleAuditEntry.Action.DELETE, pattern, before, null, operator,
                true, "rule deleted");
        body.put("success", true);
        body.put("message", "规则已删除");
        body.put("pattern", pattern);
        log.warn("删除动态限流规则 - pattern: {}, store: {}", pattern, rateRuleStore.getName());
        return body;
    }

    /**
     * 把一条规则 Map 转为完整的 {@link DynamicRateRule}（导入接口使用）。
     */
    private DynamicRateRule parseRule(Map<?, ?> item) {
        String pattern = parseStringField(item.get("pattern"));
        Integer limit = parseIntField(item.get("limit"));
        if (limit == null) {
            throw new IllegalArgumentException(
                    "缺少或非法字段 limit（-1=不限流 / 0=封禁 / >0=窗口内上限）");
        }
        Integer window = parseIntField(item.get("window"));
        if (window == null) {
            window = properties.getRateLimit().getDefaultWindow();
        }
        Long ttlSeconds = parseLongField(item.get("ttlSeconds"));
        if (ttlSeconds != null && ttlSeconds < 0) {
            throw new IllegalArgumentException("ttlSeconds 不能为负数");
        }
        long effectiveTtl = ttlSeconds != null
                ? ttlSeconds
                : properties.getRateLimit().getDynamicRules().getDefaultTtlSeconds();
        String operator = firstNonBlank(parseStringField(item.get("operator")), "import");
        String reason = parseStringField(item.get("reason"));
        Integer maxConcurrent = parseIntField(item.get("maxConcurrent"));
        if (maxConcurrent != null && maxConcurrent < -1) {
            throw new IllegalArgumentException("maxConcurrent 至少为 -1（-1=关闭，0=不干预，>0=并发上限）");
        }
        return DynamicRateRule.of(pattern, limit, window, effectiveTtl, operator, reason,
                maxConcurrent);
    }

    /**
     * 规则转管理接口输出 Map（TTL 剩余秒数 -1 表示永久）。
     */
    private Map<String, Object> ruleToMap(DynamicRateRule rule, long nowMs) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("pattern", rule.getPattern());
        item.put("limit", rule.getLimit());
        item.put("window", rule.getWindow());
        item.put("updatedAt", rule.getUpdatedAtMs());
        item.put("expiresAt", rule.getExpiresAtMs());
        item.put("ttlSecondsRemaining", rule.getTtlSecondsRemaining(nowMs));
        item.put("operator", rule.getOperator());
        item.put("reason", rule.getReason());
        item.put("maxConcurrent", rule.getMaxConcurrent());
        return item;
    }

    /**
     * 记录审计（best-effort：审计实现异常绝不影响规则变更结果）。
     */
    private void recordAudit(RuleAuditEntry.Action action, String pattern,
                             DynamicRateRule before, DynamicRateRule after,
                             String operator, boolean success, String message) {
        try {
            auditLog.record(new RuleAuditEntry(System.currentTimeMillis(), action, pattern,
                    before == null ? null : before.getLimit(),
                    before == null ? null : before.getWindow(),
                    after == null ? null : after.getLimit(),
                    after == null ? null : after.getWindow(),
                    before == null ? null : before.getMaxConcurrent(),
                    after == null ? null : after.getMaxConcurrent(),
                    after == null ? 0L : after.getExpiresAtMs(),
                    operator, currentClientIp(), success, message));
        } catch (RuntimeException e) {
            log.warn("记录规则审计失败（不影响规则变更）: {}", e.getMessage());
        }
    }

    /**
     * 当前操作人：{@code X-Governance-Operator} 请求头，缺省 {@code anonymous}。
     */
    private String currentOperator() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttributes) {
                String header = servletAttributes.getRequest()
                        .getHeader("X-Governance-Operator");
                if (header != null && !header.isBlank()) {
                    return header.trim();
                }
            }
        } catch (Throwable ignored) {
            // 非 Servlet 环境（单元测试直调）：使用 anonymous
        }
        return "anonymous";
    }

    /**
     * 当前请求来源 IP；非 HTTP 调用返回空串。
     */
    private String currentClientIp() {
        try {
            RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
            if (attrs instanceof ServletRequestAttributes servletAttributes) {
                String ip = HttpRequestContext.resolveClientIp(servletAttributes.getRequest());
                return ip == null ? "" : ip;
            }
        } catch (Throwable ignored) {
            // 非 Servlet 环境：不记录来源 IP
        }
        return "";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    private static Long parseLongField(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Long.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String parseStringField(Object value) {
        return value instanceof String s ? s.trim() : null;
    }

    private static boolean parseBooleanField(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        return value instanceof String s && Boolean.parseBoolean(s.trim());
    }

    /**
     * 解析 JSON 数值字段（兼容 Integer/Long/数字字符串），缺失或非法返回 null。
     */
    private Integer parseIntField(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String s && !s.isBlank()) {
            try {
                return Integer.valueOf(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** 分页参数单页上限，防止一次拉取过量数据。 */
    private static final int MAX_PAGE_SIZE = 500;

    /**
     * 全部 API 指标汇总（按总请求数降序）。
     *
     * <p>0.4.0 起支持分页：{@code page}（1 起）与 {@code size}（1~{@value #MAX_PAGE_SIZE}）同时提供时
     * 返回分页结构；不传参数时行为与 0.3.0 一致（返回全量数组）。
     *
     * @param page 页码（从 1 开始，可选）
     * @param size 每页条数（可选）
     */
    @GetMapping("/metrics")
    public Object metrics(@RequestParam(value = "page", required = false) Integer page,
                          @RequestParam(value = "size", required = false) Integer size) {
        List<Map<String, Object>> all = new ArrayList<>();
        for (ApiMetrics m : metricsRegistry.listAll()) {
            all.add(toSummary(m));
        }
        if (page == null && size == null) {
            return all;
        }
        int safePage = Math.max(1, page == null ? 1 : page);
        int safeSize = size == null ? 50 : Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        // 用 long 计算 fromIndex：极端 page 值下 int 乘法会溢出为负，导致 subList 越界异常；
        // 超出数据范围的页码统一返回空页（与合法的越后空页行为一致）
        long fromIndexLong = (long) (safePage - 1) * safeSize;
        int fromIndex = fromIndexLong >= all.size() ? all.size() : (int) fromIndexLong;
        int toIndex = Math.min(fromIndex + safeSize, all.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("page", safePage);
        body.put("size", safeSize);
        body.put("total", all.size());
        body.put("totalPages", (all.size() + safeSize - 1) / safeSize);
        body.put("items", all.subList(fromIndex, toIndex));
        return body;
    }

    /**
     * 单 API 指标明细（含最近请求记录）。
     *
     * @param key API 唯一标识
     */
    @GetMapping("/metrics/detail")
    public Map<String, Object> metricsDetail(@RequestParam("key") String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        ApiMetrics m = metricsRegistry.get(key);
        if (m == null) {
            body.put("found", false);
            return body;
        }
        body.put("found", true);
        body.putAll(toSummary(m));
        body.put("recentRecords", m.getRecentRecords());
        return body;
    }

    /**
     * 单 API 慢方法列表。
     *
     * @param key API 唯一标识
     */
    @GetMapping("/metrics/slow")
    public Map<String, Object> slowRequests(@RequestParam("key") String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        ApiMetrics m = metricsRegistry.get(key);
        if (m == null) {
            body.put("found", false);
            return body;
        }
        body.put("found", true);
        body.put("slowThresholdMs", properties.getLog().getSlowThresholdMs());
        body.put("slowRecords", m.getSlowRecords());
        return body;
    }

    /**
     * 获取所有 API 的慢方法记录（聚合视图）。
     *
     * <p>返回格式：
     * <pre>{
     *   "slowThresholdMs": 1000,
     *   "totalApis": 5,
     *   "slowRecords": {
     *     "com.example.UserController#getUser": [
     *       {
     *         "timestamp": 1704038400000,
     *         "elapsedMs": 1200,
     *         "success": true,
     *         "slow": true,
     *         "httpMethod": "GET",
     *         "path": "/api/user",
     *         "error": null
     *       }
     *     ],
     *     "com.example.OrderController#create": [ ... ]
     *   }
     * }</pre>
     */
    @GetMapping("/metrics/slow/all")
    public Map<String, Object> allSlowRecords() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("slowThresholdMs", properties.getLog().getSlowThresholdMs());
        
        Map<String, List<RequestRecord>> allSlow = metricsRegistry.getAllSlowRecords();
        body.put("totalApis", allSlow.size());
        body.put("slowRecords", allSlow);
        
        return body;
    }

    /**
     * 清空全部指标。
     */
    @DeleteMapping("/metrics")
    public Map<String, Object> clearMetrics() {
        if (isMutationsDisabled()) {
            return mutationsDisabledBody();
        }
        metricsRegistry.clear();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "已清空全部指标");
        return body;
    }

    /**
     * 清空指定 API 指标。
     *
     * @param key API 唯一标识
     */
    @DeleteMapping("/metrics/single")
    public Map<String, Object> clearMetric(@RequestParam("key") String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (isMutationsDisabled()) {
            return mutationsDisabledBody();
        }
        boolean removed = metricsRegistry.clear(key);
        body.put("success", removed);
        body.put("message", removed ? "已清空指标" : "未找到该 API 指标");
        return body;
    }

    // ==================== 异步方法生命周期插件（0.5.0 新增） ====================

    /**
     * 已注册的异步 Handler 清单（诊断「handler 是否注册成功 / action 是否匹配」）。
     */
    @GetMapping("/async/handlers")
    public Map<String, Object> asyncHandlers() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (asyncHandlerRegistry == null) {
            body.put("enabled", false);
            body.put("count", 0);
            body.put("handlers", List.of());
            return body;
        }
        List<AsyncHandlerInfo> infos = asyncHandlerRegistry.getHandlerInfos();
        body.put("enabled", true);
        body.put("count", infos.size());
        List<Map<String, Object>> handlers = new ArrayList<>();
        for (AsyncHandlerInfo info : infos) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("action", info.action());
            item.put("phase", info.phase().name());
            item.put("order", info.order());
            item.put("beanName", info.beanName());
            item.put("beanType", info.beanType());
            item.put("method", info.method());
            handlers.add(item);
        }
        body.put("handlers", handlers);
        return body;
    }

    /**
     * 异步插件运行状态：线程池水位（仅内置线程池可读，自定义执行器显示 custom）。
     */
    @GetMapping("/async/status")
    public Map<String, Object> asyncStatus() {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean enabled = asyncHandlerRegistry != null;
        body.put("enabled", enabled);
        body.put("registeredHandlers", enabled ? asyncHandlerRegistry.getHandlerInfos().size() : 0);
        Executor executor = asyncExecutorProvider != null ? asyncExecutorProvider.getExecutor() : null;
        if (executor instanceof ThreadPoolTaskExecutor taskExecutor) {
            ThreadPoolExecutor pool = taskExecutor.getThreadPoolExecutor();
            body.put("executorType", "thread-pool");
            body.put("corePoolSize", pool.getCorePoolSize());
            body.put("maxPoolSize", pool.getMaximumPoolSize());
            body.put("poolSize", pool.getPoolSize());
            body.put("activeCount", pool.getActiveCount());
            body.put("queueSize", pool.getQueue().size());
            body.put("queueCapacity", pool.getQueue().size() + pool.getQueue().remainingCapacity());
            body.put("completedTaskCount", pool.getCompletedTaskCount());
        } else {
            body.put("executorType", executor == null ? "none" : "custom");
        }
        return body;
    }

    // ==================== 辅助方法 ====================

    /** 掩码占位符：敏感字段非空时以该值返回。 */
    private static final String MASK = "******";

    /**
     * 构建敏感字段掩码后的配置深拷贝。
     *
     * <p>不直接返回注入的 {@link ApiGovernanceProperties} Bean 本身，避免掩码污染运行时配置；
     * 逐字段拷贝保证 JSON 输出结构与直接序列化完全一致（向后兼容）。
     */
    private ApiGovernanceProperties maskedConfig() {
        ApiGovernanceProperties masked = new ApiGovernanceProperties();
        masked.setEnabled(properties.isEnabled());
        masked.setIncludePackages(new ArrayList<>(properties.getIncludePackages()));
        masked.setExcludePackages(new ArrayList<>(properties.getExcludePackages()));

        ApiGovernanceProperties.Log srcLog = properties.getLog();
        ApiGovernanceProperties.Log dstLog = masked.getLog();
        dstLog.setEnabled(srcLog.isEnabled());
        dstLog.setLogRequestParams(srcLog.isLogRequestParams());
        dstLog.setLogResponse(srcLog.isLogResponse());
        dstLog.setSlowThresholdMs(srcLog.getSlowThresholdMs());

        ApiGovernanceProperties.RateLimit srcRl = properties.getRateLimit();
        ApiGovernanceProperties.RateLimit dstRl = masked.getRateLimit();
        dstRl.setType(srcRl.getType());
        dstRl.setAlgorithm(srcRl.getAlgorithm());
        dstRl.setDefaultLimit(srcRl.getDefaultLimit());
        dstRl.setDefaultWindow(srcRl.getDefaultWindow());
        dstRl.setStatusCode(srcRl.getStatusCode());
        dstRl.setMessage(srcRl.getMessage());
        dstRl.setFailStrategy(srcRl.getFailStrategy());
        dstRl.setMaxEntries(srcRl.getMaxEntries());
        ApiGovernanceProperties.RateLimit.DynamicRules srcRules = srcRl.getDynamicRules();
        ApiGovernanceProperties.RateLimit.DynamicRules dstRules = dstRl.getDynamicRules();
        dstRules.setEnabled(srcRules.isEnabled());
        dstRules.setRefreshIntervalMs(srcRules.getRefreshIntervalMs());
        dstRules.setMaxRules(srcRules.getMaxRules());
        dstRules.setDefaultTtlSeconds(srcRules.getDefaultTtlSeconds());
        dstRules.setAuditCapacity(srcRules.getAuditCapacity());
        dstRules.setSeedLocation(srcRules.getSeedLocation());
        dstRules.setSeedFailFast(srcRules.isSeedFailFast());

        ApiGovernanceProperties.Metrics srcMetrics = properties.getMetrics();
        ApiGovernanceProperties.Metrics dstMetrics = masked.getMetrics();
        dstMetrics.setWindowSize(srcMetrics.getWindowSize());
        dstMetrics.setWindowSeconds(srcMetrics.getWindowSeconds());
        dstMetrics.setMaxApis(srcMetrics.getMaxApis());
        dstMetrics.setMicrometerEnabled(srcMetrics.isMicrometerEnabled());

        ApiGovernanceProperties.Management srcMgmt = properties.getManagement();
        ApiGovernanceProperties.Management dstMgmt = masked.getManagement();
        dstMgmt.setEnabled(srcMgmt.isEnabled());
        dstMgmt.setBasePath(srcMgmt.getBasePath());
        dstMgmt.setAuthHeader(srcMgmt.getAuthHeader());
        dstMgmt.setMutationsEnabled(srcMgmt.isMutationsEnabled());
        dstMgmt.setHealthEnabled(srcMgmt.isHealthEnabled());
        dstMgmt.setHealthStaleMultiplier(srcMgmt.getHealthStaleMultiplier());
        // 鉴权令牌：非空即掩码，绝不回显明文
        dstMgmt.setAuthToken(maskIfPresent(srcMgmt.getAuthToken()));

        ApiGovernanceProperties.Async srcAsync = properties.getAsync();
        ApiGovernanceProperties.Async dstAsync = masked.getAsync();
        dstAsync.setEnabled(srcAsync.isEnabled());
        dstAsync.setCorePoolSize(srcAsync.getCorePoolSize());
        dstAsync.setMaxPoolSize(srcAsync.getMaxPoolSize());
        dstAsync.setQueueCapacity(srcAsync.getQueueCapacity());
        dstAsync.setKeepAliveSeconds(srcAsync.getKeepAliveSeconds());
        dstAsync.setThreadNamePrefix(srcAsync.getThreadNamePrefix());
        dstAsync.setAwaitTerminationSeconds(srcAsync.getAwaitTerminationSeconds());
        dstAsync.setIgnoreUnmatchedHandlers(srcAsync.isIgnoreUnmatchedHandlers());
        dstAsync.setWebContextEnrichment(srcAsync.isWebContextEnrichment());

        ApiGovernanceProperties.Alert srcAlert = properties.getAlert();
        ApiGovernanceProperties.Alert dstAlert = masked.getAlert();
        dstAlert.setEnabled(srcAlert.isEnabled());
        dstAlert.setSuppressIntervalMs(srcAlert.getSuppressIntervalMs());
        dstAlert.setRecoveryEnabled(srcAlert.isRecoveryEnabled());
        ApiGovernanceProperties.Webhook srcWebhook = srcAlert.getWebhook();
        ApiGovernanceProperties.Webhook dstWebhook = dstAlert.getWebhook();
        dstWebhook.setEnabled(srcWebhook.isEnabled());
        // webhook 地址的 query 参数（如钉钉机器人的 access_token）属于敏感凭据，
        // 只保留协议与主机路径供诊断，query 部分统一掩码
        dstWebhook.setUrl(maskUrlQuery(srcWebhook.getUrl()));
        dstWebhook.setTimeoutMs(srcWebhook.getTimeoutMs());
        dstWebhook.setPlatform(srcWebhook.getPlatform());
        // Webhook 令牌与加签密钥：非空即掩码，绝不回显明文
        dstWebhook.setSecretToken(maskIfPresent(srcWebhook.getSecretToken()));
        dstWebhook.setSignSecret(maskIfPresent(srcWebhook.getSignSecret()));

        ApiGovernanceProperties.Tracing srcTracing = properties.getTracing();
        ApiGovernanceProperties.Tracing dstTracing = masked.getTracing();
        dstTracing.setEnabled(srcTracing.isEnabled());
        dstTracing.setAsyncContextPropagation(srcTracing.isAsyncContextPropagation());
        dstTracing.setKafka(srcTracing.isKafka());
        dstTracing.setRabbit(srcTracing.isRabbit());

        ApiGovernanceProperties.Filters srcFilters = properties.getFilters();
        ApiGovernanceProperties.Filters dstFilters = masked.getFilters();
        dstFilters.setMetadataCollector(srcFilters.isMetadataCollector());
        dstFilters.setTrafficStatistics(srcFilters.isTrafficStatistics());
        dstFilters.setRateLimit(srcFilters.isRateLimit());
        dstFilters.setSlowMethod(srcFilters.isSlowMethod());
        dstFilters.setLogging(srcFilters.isLogging());

        ApiGovernanceProperties.Concurrency srcConcurrency = properties.getConcurrencyLimit();
        ApiGovernanceProperties.Concurrency dstConcurrency = masked.getConcurrencyLimit();
        dstConcurrency.setEnabled(srcConcurrency.isEnabled());
        dstConcurrency.setDefaultPermits(srcConcurrency.getDefaultPermits());
        dstConcurrency.setMaxEntries(srcConcurrency.getMaxEntries());
        dstConcurrency.setIdleTimeoutMs(srcConcurrency.getIdleTimeoutMs());
        dstConcurrency.setRejectStatus(srcConcurrency.getRejectStatus());
        dstConcurrency.setMessage(srcConcurrency.getMessage());

        ApiGovernanceProperties.BodySizeLimit srcBody = properties.getBodySizeLimit();
        ApiGovernanceProperties.BodySizeLimit dstBody = masked.getBodySizeLimit();
        dstBody.setEnabled(srcBody.isEnabled());
        dstBody.setMaxBytes(srcBody.getMaxBytes());
        dstBody.setRejectStatus(srcBody.getRejectStatus());
        dstBody.setMessage(srcBody.getMessage());

        masked.getProblemDetails().setEnabled(properties.getProblemDetails().isEnabled());

        return masked;
    }

    /**
     * 管理端点写操作是否被禁用（{@code management.mutations-enabled=false}）。
     */
    private boolean isMutationsDisabled() {
        return !properties.getManagement().isMutationsEnabled();
    }

    /**
     * 写操作被禁用时的统一响应体。
     */
    private Map<String, Object> mutationsDisabledBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("message", "管理接口写操作已禁用 (api.governance.management.mutations-enabled=false)");
        return body;
    }

    /**
     * 非空白字符串掩码为 {@value MASK}；null/空白原样返回（保持「未配置」语义）。
     */
    private String maskIfPresent(String value) {
        return (value != null && !value.trim().isEmpty()) ? MASK : value;
    }

    /**
     * 掩码 URL 的 query 部分：钉钉/企微/飞书机器人地址的 query 中通常携带 access_token
     * 等机器人凭据，管理接口回显时只保留协议、主机与路径，query 统一替换为 {@value MASK}。
     * 无 query 的地址原样返回。
     */
    private String maskUrlQuery(String url) {
        if (url == null || url.isEmpty()) {
            return url;
        }
        int queryStart = url.indexOf('?');
        return queryStart >= 0 ? url.substring(0, queryStart) + "?" + MASK : url;
    }

    /**
     * 将过滤器列表转换为可序列化描述。
     */
    private List<Map<String, Object>> describe(List<? extends Filter> filters) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Filter f : filters) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", f.getName());
            item.put("order", f.getOrder());
            item.put("enabled", f.isEnabled());
            item.put("kind", f instanceof PreFilter ? "PRE" : (f instanceof PostFilter ? "POST" : "UNKNOWN"));
            result.add(item);
        }
        return result;
    }

    /**
     * 将 API 指标转换为汇总 Map。
     */
    private Map<String, Object> toSummary(ApiMetrics m) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("apiKey", m.getApiKey());
        s.put("total", m.getTotalRequests());
        s.put("success", m.getSuccessRequests());
        s.put("fail", m.getFailRequests());
        s.put("reject", m.getRejectRequests());
        s.put("slow", m.getSlowRequests());
        s.put("avgMs", m.getAvgElapsedMs());
        s.put("maxMs", m.getMaxElapsedMs());
        s.put("minMs", m.getMinElapsedMs());
        return s;
    }
}
