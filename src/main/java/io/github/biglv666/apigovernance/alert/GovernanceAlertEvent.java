package io.github.biglv666.apigovernance.alert;

import java.time.Instant;

/**
 * 治理告警事件 —— 不可变的事件快照，由治理管道在检测到异常状况时创建，
 * 分发给所有 {@link GovernanceAlertNotifier}。
 *
 * <h3>安全边界</h3>
 * <p>与异步模块的事件快照原则一致：事件<b>只携带元数据</b>（API 标识、路径、耗时、类型等），
 * 不捕获方法入参、返回值与原始异常堆栈，避免敏感信息经告警渠道外泄。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class GovernanceAlertEvent {

    /**
     * 告警类型。
     */
    public enum Type {

        /** 慢方法：请求耗时超过 {@code api.governance.log.slow-threshold-ms}。 */
        SLOW_METHOD,

        /** 限流拒绝：请求被限流器拒绝。 */
        RATE_LIMIT_REJECT,

        /** 限流器故障：限流器（如 Redis）执行异常，按 fail-strategy 降级处理。 */
        RATE_LIMITER_FAILURE,

        /** 异步任务被拒绝（0.5.0 新增）：框架线程池队列满且达到最大线程数。 */
        ASYNC_TASK_REJECTED
    }

    private final Type type;
    private final String apiKey;
    private final String httpMethod;
    private final String path;
    private final String message;
    private final long elapsedMs;
    private final long thresholdMs;
    private final long timestampMs;

    private GovernanceAlertEvent(Type type, String apiKey, String httpMethod, String path,
                                 String message, long elapsedMs, long thresholdMs, long timestampMs) {
        this.type = type;
        this.apiKey = apiKey;
        this.httpMethod = httpMethod;
        this.path = path;
        this.message = message;
        this.elapsedMs = elapsedMs;
        this.thresholdMs = thresholdMs;
        this.timestampMs = timestampMs;
    }

    /**
     * 创建慢方法告警事件。
     *
     * @param apiKey      API 唯一标识
     * @param httpMethod  HTTP 方法（可能为 null）
     * @param path        请求路径（可能为 null）
     * @param elapsedMs   实际耗时（毫秒）
     * @param thresholdMs 慢方法阈值（毫秒）
     * @return 告警事件
     */
    public static GovernanceAlertEvent slowMethod(String apiKey, String httpMethod, String path,
                                                  long elapsedMs, long thresholdMs) {
        return new GovernanceAlertEvent(Type.SLOW_METHOD, apiKey, httpMethod, path,
                "慢方法告警: 耗时 " + elapsedMs + "ms 超过阈值 " + thresholdMs + "ms",
                elapsedMs, thresholdMs, System.currentTimeMillis());
    }

    /**
     * 创建限流拒绝告警事件。
     *
     * @param apiKey     API 唯一标识
     * @param httpMethod HTTP 方法（可能为 null）
     * @param path       请求路径（可能为 null）
     * @param reason     拒绝原因
     * @return 告警事件
     */
    public static GovernanceAlertEvent rateLimitReject(String apiKey, String httpMethod,
                                                       String path, String reason) {
        return new GovernanceAlertEvent(Type.RATE_LIMIT_REJECT, apiKey, httpMethod, path,
                "限流拒绝: " + reason, -1, -1, System.currentTimeMillis());
    }

    /**
     * 创建限流器故障告警事件。
     *
     * @param rateLimiterName 限流器名称
     * @param error           故障摘要（仅异常 message，不含堆栈）
     * @return 告警事件
     */
    public static GovernanceAlertEvent rateLimiterFailure(String rateLimiterName, String error) {
        return new GovernanceAlertEvent(Type.RATE_LIMITER_FAILURE, rateLimiterName, null, null,
                "限流器故障: " + error, -1, -1, System.currentTimeMillis());
    }

    /**
     * 创建异步任务被拒绝告警事件（0.5.0 新增）。
     *
     * @param action   异步动作名
     * @param handler  Handler 方法标识（完整方法签名）
     * @param error    拒绝原因摘要
     * @return 告警事件
     */
    public static GovernanceAlertEvent asyncTaskRejected(String action, String handler, String error) {
        return new GovernanceAlertEvent(Type.ASYNC_TASK_REJECTED, action, null, null,
                "异步任务被拒绝: handler=" + handler + ", 原因=" + error,
                -1, -1, System.currentTimeMillis());
    }

    public Type getType() {
        return type;
    }

    /** API 唯一标识（限流器故障事件中为限流器名称）。 */
    public String getApiKey() {
        return apiKey;
    }

    /** HTTP 方法（可能为 null）。 */
    public String getHttpMethod() {
        return httpMethod;
    }

    /** 请求路径（可能为 null）。 */
    public String getPath() {
        return path;
    }

    /** 人类可读的告警描述。 */
    public String getMessage() {
        return message;
    }

    /** 实际耗时（毫秒）；不适用的事件类型为 -1。 */
    public long getElapsedMs() {
        return elapsedMs;
    }

    /** 阈值（毫秒）；不适用的事件类型为 -1。 */
    public long getThresholdMs() {
        return thresholdMs;
    }

    /** 事件产生时间戳（毫秒）。 */
    public long getTimestampMs() {
        return timestampMs;
    }

    @Override
    public String toString() {
        return "[" + type + "] " + Instant.ofEpochMilli(timestampMs) + " api=" + apiKey
                + " " + httpMethod + " " + path + " - " + message;
    }
}
