package io.github.biglv666.apigovernance.management;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.internal.AsyncHandlerRegistry;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.annotation.Skip;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import io.github.biglv666.apigovernance.filter.Filter;
import io.github.biglv666.apigovernance.filter.FilterChain;
import io.github.biglv666.apigovernance.filter.PostFilter;
import io.github.biglv666.apigovernance.filter.PreFilter;
import io.github.biglv666.apigovernance.metrics.ApiMetrics;
import io.github.biglv666.apigovernance.metrics.MetricsRegistry;
import io.github.biglv666.apigovernance.metrics.RequestRecord;
import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import io.github.biglv666.apigovernance.ratelimit.local.SlidingWindowRateLimiter;
import io.github.biglv666.apigovernance.ratelimit.local.TokenBucketRateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    /**
     * 构造管理控制器。
     *
     * @param properties     全局配置
     * @param rateLimiter    限流器（可能为 null）
     * @param filterChain    过滤器链
     * @param metricsRegistry 指标注册表
     * @param asyncHandlerRegistry 异步 Handler 注册表（可能为 null：异步插件关闭时）
     * @param asyncExecutorProvider 异步执行器（可能为 null：异步插件关闭时）
     */
    public GovernanceManagementController(ApiGovernanceProperties properties,
                                          RateLimiter rateLimiter,
                                          FilterChain filterChain,
                                          MetricsRegistry metricsRegistry,
                                          AsyncHandlerRegistry asyncHandlerRegistry,
                                          AsyncExecutorProvider asyncExecutorProvider) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.filterChain = filterChain;
        this.metricsRegistry = metricsRegistry;
        this.asyncHandlerRegistry = asyncHandlerRegistry;
        this.asyncExecutorProvider = asyncExecutorProvider;
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
     * <p>{@code management.auth-token} 与 {@code alert.webhook.secret-token} 非空时以
     * {@value #MASK} 返回，避免令牌经管理接口明文回显泄露；其余配置项原样输出。
     * 注意 {@code alert.webhook.url} 原样返回 —— 钉钉/企微等机器人地址的 query 参数中
     * 可能携带 access_token，是否对外暴露由使用方决定（生产环境建议开启管理接口鉴权）。
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
        int fromIndex = Math.min((safePage - 1) * safeSize, all.size());
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

        ApiGovernanceProperties.Alert srcAlert = properties.getAlert();
        ApiGovernanceProperties.Alert dstAlert = masked.getAlert();
        dstAlert.setEnabled(srcAlert.isEnabled());
        dstAlert.setSuppressIntervalMs(srcAlert.getSuppressIntervalMs());
        ApiGovernanceProperties.Webhook srcWebhook = srcAlert.getWebhook();
        ApiGovernanceProperties.Webhook dstWebhook = dstAlert.getWebhook();
        dstWebhook.setEnabled(srcWebhook.isEnabled());
        dstWebhook.setUrl(srcWebhook.getUrl());
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
