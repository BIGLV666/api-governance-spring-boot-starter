package io.github.biglv666.apigovernance.metrics.micrometer;

import io.github.biglv666.apigovernance.metrics.MetricsEventListener;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Micrometer 指标桥接监听器 —— 把治理事件同步暴露为标准 Micrometer 指标，
 * 使宿主应用可直接通过 {@code /actuator/prometheus}、Grafana、Datadog 等标准生态观测。
 *
 * <h3>暴露的指标</h3>
 * <table>
 *   <tr><th>指标</th><th>类型</th><th>标签</th><th>说明</th></tr>
 *   <tr><td>{@code api.governance.requests}</td><td>Counter</td>
 *       <td>api, method, outcome</td><td>请求总数；outcome ∈ success/error/reject</td></tr>
 *   <tr><td>{@code api.governance.request.duration}</td><td>Timer</td>
 *       <td>api, method</td><td>请求耗时分布（不含被拒绝请求）</td></tr>
 *   <tr><td>{@code api.governance.apis.tracked}</td><td>Gauge</td>
 *       <td>无</td><td>当前统计的 API 数量（由自动配置单独注册，数据源为内存注册表）</td></tr>
 * </table>
 *
 * <h3>标签基数说明</h3>
 * <p>{@code api} 标签取值为 {@code 全限定类名#方法名}，基数上限为 Controller 方法数量，
 * 与内存指标的 {@code max-apis} 同量级，无标签基数膨胀风险。注意：Micrometer 的
 * Meter 一旦创建即常驻（不受内存注册表 LRU 淘汰与 DELETE 指标清空影响），
 * 清理需通过 Micrometer 自身的 {@code meterRegistry.clear()} 或进程重启。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class MicrometerMetricsEventListener implements MetricsEventListener {

    /** 请求计数指标名。 */
    public static final String METRIC_REQUESTS = "api.governance.requests";

    /** 请求耗时指标名。 */
    public static final String METRIC_DURATION = "api.governance.request.duration";

    /** 统计 API 数量指标名。 */
    public static final String METRIC_APIS_TRACKED = "api.governance.apis.tracked";

    /** 标签：API 唯一标识。 */
    public static final String TAG_API = "api";

    /** 标签：HTTP 方法。 */
    public static final String TAG_METHOD = "method";

    /** 标签：请求结果（success / error / reject）。 */
    public static final String TAG_OUTCOME = "outcome";

    private static final String OUTCOME_SUCCESS = "success";
    private static final String OUTCOME_ERROR = "error";
    private static final String OUTCOME_REJECT = "reject";
    private static final String UNKNOWN = "unknown";

    private final MeterRegistry meterRegistry;

    /**
     * 构造监听器。
     *
     * @param meterRegistry Micrometer 注册表；为 null 时本监听器空转（用于容器未提供
     *                      MeterRegistry 的场景，避免自动装配对装配顺序的依赖）
     */
    public MicrometerMetricsEventListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void onResult(String apiKey, long elapsedMs, boolean success, boolean slow,
                         String httpMethod, String path, String error) {
        if (meterRegistry == null) {
            return;
        }
        // 计数器：按结果细分；slow 可通过 api.governance.requests{outcome=success} 与
        // Timer 的 count 对比近似观察，故不再增加 slow 标签，控制标签维度
        Counter.builder(METRIC_REQUESTS)
                .tag(TAG_API, apiKey)
                .tag(TAG_METHOD, orUnknown(httpMethod))
                .tag(TAG_OUTCOME, success ? OUTCOME_SUCCESS : OUTCOME_ERROR)
                .description("Total requests governed by api-governance")
                .register(meterRegistry)
                .increment();

        // 耗时分布：只记录业务方法真实执行（含失败）的请求
        Timer.builder(METRIC_DURATION)
                .tag(TAG_API, apiKey)
                .tag(TAG_METHOD, orUnknown(httpMethod))
                .description("Request duration governed by api-governance")
                .register(meterRegistry)
                .record(elapsedMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onReject(String apiKey, String httpMethod, String path, String reason) {
        if (meterRegistry == null) {
            return;
        }
        Counter.builder(METRIC_REQUESTS)
                .tag(TAG_API, apiKey)
                .tag(TAG_METHOD, orUnknown(httpMethod))
                .tag(TAG_OUTCOME, OUTCOME_REJECT)
                .description("Total requests governed by api-governance")
                .register(meterRegistry)
                .increment();
    }

    /**
     * Micrometer 标签值不允许为 null，采集失败的请求统一归为 unknown。
     */
    private String orUnknown(String httpMethod) {
        return httpMethod == null ? UNKNOWN : httpMethod;
    }
}
