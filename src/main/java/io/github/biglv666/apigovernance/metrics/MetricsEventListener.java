package io.github.biglv666.apigovernance.metrics;

/**
 * 指标事件监听器插件 —— 「一切皆插件」在可观测性维度的落地。
 *
 * <p>{@link MetricsRegistry} 是所有治理事件（请求完成、请求被拒绝）的汇聚点，
 * 本接口允许在事件发生时获得同步回调，用于把指标桥接到任意下游，例如：
 * <ul>
 *   <li>Micrometer / Prometheus（内置 {@code MicrometerMetricsEventListener}）；</li>
 *   <li>告警分发（内置 {@code AlertDispatcher}）；</li>
 *   <li>用户自定义的日志、上报、看板等任意系统。</li>
 * </ul>
 *
 * <h3>使用方式</h3>
 * <p>将实现注册为 Spring Bean 即自动接入（监听器列表在自动装配期收集）。
 * 两个方法均为 default 空实现，按需覆盖其一即可。
 *
 * <h3>重要约束</h3>
 * <ul>
 *   <li>回调在<b>请求线程上同步执行</b>，实现必须轻量、快速返回，绝不能阻塞或抛出异常
 *       （抛出异常会被注册表吞掉并记 warn 日志，但会拖慢请求）；耗时操作请自行异步化；</li>
 *   <li>回调携带的均为元数据（API 标识、耗时、路径等），不含方法入参、返回值与异常堆栈；</li>
 *   <li>监听器不影响任何治理行为，注册表对监听器异常完全隔离。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public interface MetricsEventListener {

    /**
     * 请求完成事件（业务方法已执行完毕，成功或失败；被拒绝的请求不触发本事件）。
     *
     * @param apiKey     API 唯一标识（全限定类名#方法名）
     * @param elapsedMs  执行耗时（毫秒）
     * @param success    是否成功（业务方法未抛异常）
     * @param slow       是否为慢方法（耗时超过 {@code api.governance.log.slow-threshold-ms}）
     * @param httpMethod HTTP 方法（GET/POST 等，采集失败时可能为 null）
     * @param path       请求路径（采集失败时可能为 null）
     * @param error      错误信息（成功时为 null；仅异常 message，不含堆栈）
     */
    default void onResult(String apiKey, long elapsedMs, boolean success, boolean slow,
                          String httpMethod, String path, String error) {
        // 默认不处理
    }

    /**
     * 请求被拒绝事件（限流、限流器故障拒绝等前置短路场景）。
     *
     * @param apiKey     API 唯一标识
     * @param httpMethod HTTP 方法（可能为 null）
     * @param path       请求路径（可能为 null）
     * @param reason     拒绝原因
     */
    default void onReject(String apiKey, String httpMethod, String path, String reason) {
        // 默认不处理
    }
}
