package io.github.biglv666.apigovernance.metrics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个 API 的运行时指标。
 *
 * <p>每个被拦截的 Controller 方法对应一个 {@code ApiMetrics} 实例，由 {@link MetricsRegistry}
 * 统一管理。实例内部保存：
 * <ul>
 *   <li><b>聚合计数器</b>：总请求数、成功数、失败数、拒绝数、慢方法数；</li>
 *   <li><b>耗时统计</b>：最小/最大/累计耗时（用于计算平均值）；</li>
 *   <li><b>有界滑动窗口</b>：最近若干条请求记录，用于查询明细与慢方法列表。</li>
 * </ul>
 *
 * <p>所有计数均采用 {@link AtomicLong} 保证无锁线程安全；最近记录使用有界
 * {@link SlidingWindow}，从根本上避免内存膨胀。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class ApiMetrics {

    /** API 唯一标识（全限定类名#方法名）。 */
    private final String apiKey;

    /** 总请求数（请求进入治理管道时 +1）。 */
    private final AtomicLong totalRequests = new AtomicLong();

    /** 成功请求数。 */
    private final AtomicLong successRequests = new AtomicLong();

    /** 失败请求数（业务方法抛异常）。 */
    private final AtomicLong failRequests = new AtomicLong();

    /** 被拒绝请求数（限流/校验拒绝）。 */
    private final AtomicLong rejectRequests = new AtomicLong();

    /** 慢方法请求数（耗时超过阈值）。 */
    private final AtomicLong slowRequests = new AtomicLong();

    /** 累计耗时（毫秒），配合 totalRequests 计算平均耗时。 */
    private final AtomicLong totalElapsedMs = new AtomicLong();

    /** 最小耗时（毫秒），初始为最大值。 */
    private final AtomicLong minElapsedMs = new AtomicLong(Long.MAX_VALUE);

    /** 最大耗时（毫秒）。 */
    private final AtomicLong maxElapsedMs = new AtomicLong();

    /** 最近请求记录的有界滑动窗口。 */
    private final SlidingWindow<RequestRecord> recentRecords;

    /** 最近访问时间戳，用于注册表超限时按 LRU 淘汰。 */
    private final AtomicLong lastAccessTime = new AtomicLong(System.currentTimeMillis());

    /**
     * 构造 API 指标。
     *
     * @param apiKey       API 唯一标识
     * @param windowSize   最近记录窗口的条数上限
     * @param windowMillis 最近记录窗口的时间长度（毫秒）
     */
    public ApiMetrics(String apiKey, int windowSize, long windowMillis) {
        this.apiKey = apiKey;
        this.recentRecords = new SlidingWindow<>(windowSize, windowMillis);
    }

    /**
     * 刷新最近访问时间（用于 LRU 淘汰策略）。
     */
    void touch() {
        lastAccessTime.set(System.currentTimeMillis());
    }

    /**
     * 记录一次「请求进入」事件（由流量统计前置过滤器调用）。
     */
    void recordStart() {
        touch();
        totalRequests.incrementAndGet();
    }

    /**
     * 记录一次「请求完成」事件（由慢方法后置过滤器调用）。
     *
     * @param elapsedMs 执行耗时（毫秒）
     * @param success   是否成功
     * @param slow      是否为慢方法
     * @param httpMethod HTTP 方法
     * @param path      请求路径
     * @param error     错误信息（成功时为 null）
     */
    void recordResult(long elapsedMs, boolean success, boolean slow,
                      String httpMethod, String path, String error) {
        touch();
        if (success) {
            successRequests.incrementAndGet();
        } else {
            failRequests.incrementAndGet();
        }
        if (slow) {
            slowRequests.incrementAndGet();
        }
        totalElapsedMs.addAndGet(elapsedMs);
        minElapsedMs.accumulateAndGet(elapsedMs, Math::min);
        maxElapsedMs.accumulateAndGet(elapsedMs, Math::max);

        RequestRecord record = new RequestRecord(
                System.currentTimeMillis(), elapsedMs, success, slow, httpMethod, path, error);
        recentRecords.add(record);
    }

    /**
     * 记录一次「拒绝」事件（由限流/校验前置过滤器调用）。
     *
     * @param httpMethod HTTP 方法
     * @param path       请求路径
     * @param reason     拒绝原因
     */
    void recordReject(String httpMethod, String path, String reason) {
        touch();
        rejectRequests.incrementAndGet();
        RequestRecord record = new RequestRecord(
                System.currentTimeMillis(), 0L, false, false, httpMethod, path, reason);
        recentRecords.add(record);
    }

    /** 获取最近访问时间（毫秒）。 */
    long getLastAccessTime() {
        return lastAccessTime.get();
    }

    // ==================== 只读快照方法（供管理接口调用） ====================

    public String getApiKey() {
        return apiKey;
    }

    public long getTotalRequests() {
        return totalRequests.get();
    }

    public long getSuccessRequests() {
        return successRequests.get();
    }

    public long getFailRequests() {
        return failRequests.get();
    }

    public long getRejectRequests() {
        return rejectRequests.get();
    }

    public long getSlowRequests() {
        return slowRequests.get();
    }

    public long getTotalElapsedMs() {
        return totalElapsedMs.get();
    }

    public long getMinElapsedMs() {
        long v = minElapsedMs.get();
        return v == Long.MAX_VALUE ? 0 : v;
    }

    public long getMaxElapsedMs() {
        return maxElapsedMs.get();
    }

    /**
     * 计算平均耗时（毫秒），无请求时返回 0。
     *
     * @return 平均耗时
     */
    public double getAvgElapsedMs() {
        long total = totalRequests.get();
        return total == 0 ? 0 : (double) totalElapsedMs.get() / total;
    }

    /**
     * 获取最近请求记录快照（时间从旧到新）。
     *
     * @return 最近请求记录列表
     */
    public List<RequestRecord> getRecentRecords() {
        return recentRecords.snapshot();
    }

    /**
     * 获取慢方法记录（从最近记录中过滤 {@code slow == true} 的条目）。
     *
     * <p>返回列表按时间<b>倒序</b>排列（最新的在前），便于查看最近的慢请求。
     *
     * @return 慢方法记录列表（时间倒序）
     */
    public List<RequestRecord> getSlowRecords() {
        List<RequestRecord> all = recentRecords.snapshot();
        List<RequestRecord> result = new ArrayList<>();
        // 从后往前遍历（snapshot 是从旧到新），这样结果就是从新到旧
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).isSlow()) {
                result.add(all.get(i));
            }
        }
        return result;
    }
}
