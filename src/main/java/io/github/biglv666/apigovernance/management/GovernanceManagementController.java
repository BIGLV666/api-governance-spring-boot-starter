package io.github.biglv666.apigovernance.management;

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

    /**
     * 构造管理控制器。
     *
     * @param properties     全局配置
     * @param rateLimiter    限流器（可能为 null）
     * @param filterChain    过滤器链
     * @param metricsRegistry 指标注册表
     */
    public GovernanceManagementController(ApiGovernanceProperties properties,
                                          RateLimiter rateLimiter,
                                          FilterChain filterChain,
                                          MetricsRegistry metricsRegistry) {
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.filterChain = filterChain;
        this.metricsRegistry = metricsRegistry;
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
     * 当前配置。
     */
    @GetMapping("/config")
    public ApiGovernanceProperties config() {
        return properties;
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

    /**
     * 全部 API 指标汇总（按总请求数降序）。
     */
    @GetMapping("/metrics")
    public List<Map<String, Object>> metrics() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ApiMetrics m : metricsRegistry.listAll()) {
            result.add(toSummary(m));
        }
        return result;
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
        boolean removed = metricsRegistry.clear(key);
        body.put("success", removed);
        body.put("message", removed ? "已清空指标" : "未找到该 API 指标");
        return body;
    }

    // ==================== 辅助方法 ====================

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
