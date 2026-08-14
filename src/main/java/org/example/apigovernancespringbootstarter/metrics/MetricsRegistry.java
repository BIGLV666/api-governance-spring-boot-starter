package org.example.apigovernancespringbootstarter.metrics;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存指标注册表 —— 集中管理所有 API 的运行时指标。
 *
 * <p>数据全部保存在进程内存中（随进程启动而创建、随进程关闭而销毁），不做持久化。
 * 为防止 {@code apiKey -> ApiMetrics} 映射无限扩大，注册表设置<b>最大 API 数量</b>：
 * 当超出上限时，按 LRU（最近最少使用）淘汰最久未访问的 API 指标。
 *
 * <p>真实项目中，Controller 方法数量是有限的（通常远小于上限），因此绝大多数场景不会触发淘汰；
 * 该上限仅作为兜底保护，避免动态 key（如自定义限流键）导致内存膨胀。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class MetricsRegistry {

    /** 最大管理的 API 数量（兜底上限）。 */
    private final int maxApis;

    /** 每个 API 最近记录窗口的条数上限。 */
    private final int windowSize;

    /** 每个 API 最近记录窗口的时间长度（毫秒）。 */
    private final long windowMillis;

    /** apiKey -> 指标 的并发映射。 */
    private final Map<String, ApiMetrics> registry = new ConcurrentHashMap<>();

    /**
     * 构造指标注册表。
     *
     * @param maxApis      最大管理的 API 数量
     * @param windowSize   每个 API 最近记录窗口条数上限
     * @param windowMillis 每个 API 最近记录窗口时间长度（毫秒）
     */
    public MetricsRegistry(int maxApis, int windowSize, long windowMillis) {
        this.maxApis = Math.max(1, maxApis);
        this.windowSize = Math.max(1, windowSize);
        this.windowMillis = windowMillis;
    }

    /**
     * 记录一次「请求进入」事件。
     *
     * @param apiKey API 唯一标识
     */
    public void recordStart(String apiKey) {
        getOrCreate(apiKey).recordStart();
    }

    /**
     * 记录一次「请求完成」事件。
     *
     * @param apiKey     API 唯一标识
     * @param elapsedMs  执行耗时（毫秒）
     * @param success    是否成功
     * @param slow       是否为慢方法
     * @param httpMethod HTTP 方法
     * @param path       请求路径
     * @param error      错误信息（成功时为 null）
     */
    public void recordResult(String apiKey, long elapsedMs, boolean success, boolean slow,
                             String httpMethod, String path, String error) {
        getOrCreate(apiKey).recordResult(elapsedMs, success, slow, httpMethod, path, error);
    }

    /**
     * 记录一次「拒绝」事件。
     *
     * @param apiKey     API 唯一标识
     * @param httpMethod HTTP 方法
     * @param path       请求路径
     * @param reason     拒绝原因
     */
    public void recordReject(String apiKey, String httpMethod, String path, String reason) {
        getOrCreate(apiKey).recordReject(httpMethod, path, reason);
    }

    /**
     * 获取指定 API 的指标；不存在时返回 null。
     *
     * @param apiKey API 唯一标识
     * @return API 指标，或 null
     */
    public ApiMetrics get(String apiKey) {
        ApiMetrics metrics = registry.get(apiKey);
        if (metrics != null) {
            metrics.touch();
        }
        return metrics;
    }

    /**
     * 列出所有 API 指标（按总请求数降序）。
     *
     * @return 指标列表
     */
    public List<ApiMetrics> listAll() {
        List<ApiMetrics> list = new ArrayList<>(registry.values());
        list.sort(Comparator.comparingLong(ApiMetrics::getTotalRequests).reversed());
        return list;
    }

    /**
     * 清空全部指标。
     */
    public void clear() {
        registry.clear();
    }

    /**
     * 清空指定 API 的指标。
     *
     * @param apiKey API 唯一标识
     * @return 是否存在并已清除
     */
    public boolean clear(String apiKey) {
        return registry.remove(apiKey) != null;
    }

    /**
     * 获取当前管理的 API 数量。
     *
     * @return API 数量
     */
    public int size() {
        return registry.size();
    }

    /**
     * 获取或创建指标，并在超限时执行 LRU 淘汰。
     *
     * @param apiKey API 唯一标识
     * @return API 指标实例
     */
    private ApiMetrics getOrCreate(String apiKey) {
        ApiMetrics metrics = registry.get(apiKey);
        if (metrics != null) {
            return metrics;
        }
        // 创建新指标前做一次容量保护
        evictIfNeeded();
        return registry.computeIfAbsent(apiKey,
                k -> new ApiMetrics(k, windowSize, windowMillis));
    }

    /**
     * 当注册表达到上限时，淘汰「最久未访问」的 API 指标。
     *
     * <p>该方法仅在「新增不存在的 key」时调用，属于低频路径，允许 O(n) 扫描。
     */
    private void evictIfNeeded() {
        if (registry.size() < maxApis) {
            return;
        }
        String oldestKey = null;
        long oldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, ApiMetrics> entry : registry.entrySet()) {
            long access = entry.getValue().getLastAccessTime();
            if (access < oldestTime) {
                oldestTime = access;
                oldestKey = entry.getKey();
            }
        }
        if (oldestKey != null) {
            registry.remove(oldestKey);
        }
    }

    /**
     * 获取所有 API 的慢方法记录（聚合视图）。
     *
     * <p>返回 Map 格式：{@code apiKey -> List<RequestRecord>}，
     * 每个 API 的慢请求列表按时间倒序排列（最新的在前）。
     * 返回的 Map 是快照，对它的修改不影响内部数据。
     *
     * @return 所有 API 的慢方法记录聚合
     */
    public Map<String, List<RequestRecord>> getAllSlowRecords() {
        Map<String, List<RequestRecord>> result = new HashMap<>();
        for (Map.Entry<String, ApiMetrics> entry : registry.entrySet()) {
            List<RequestRecord> slowRecords = entry.getValue().getSlowRecords();
            if (!slowRecords.isEmpty()) {
                result.put(entry.getKey(), slowRecords);
            }
        }
        return result;
    }
}
