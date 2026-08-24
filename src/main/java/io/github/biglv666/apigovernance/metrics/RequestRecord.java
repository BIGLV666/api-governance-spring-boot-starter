package io.github.biglv666.apigovernance.metrics;

/**
 * 单次请求的记录快照 —— 保存在 {@link ApiMetrics} 的有界滑动窗口中。
 *
 * <p>记录的是「响应情况」，包括耗时、成功/失败、是否慢方法、HTTP 方法、路径与错误信息，
 * 供管理接口查询近期请求明细与慢方法列表。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class RequestRecord {

    /** 请求完成时间戳（毫秒）。 */
    private final long timestamp;

    /** 执行耗时（毫秒）。 */
    private final long elapsedMs;

    /** 是否成功（无异常）。 */
    private final boolean success;

    /** 是否为慢方法（耗时超过阈值）。 */
    private final boolean slow;

    /** HTTP 方法（GET/POST 等）。 */
    private final String httpMethod;

    /** 请求路径（含路径变量模式）。 */
    private final String path;

    /** 错误信息（成功时为 null）。 */
    private final String error;

    public RequestRecord(long timestamp, long elapsedMs, boolean success, boolean slow,
                         String httpMethod, String path, String error) {
        this.timestamp = timestamp;
        this.elapsedMs = elapsedMs;
        this.success = success;
        this.slow = slow;
        this.httpMethod = httpMethod;
        this.path = path;
        this.error = error;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public long getElapsedMs() {
        return elapsedMs;
    }

    public boolean isSuccess() {
        return success;
    }

    public boolean isSlow() {
        return slow;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public String getPath() {
        return path;
    }

    public String getError() {
        return error;
    }
}
