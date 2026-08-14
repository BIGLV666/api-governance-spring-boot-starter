package org.example.apigovernancespringbootstarter.filter;

import org.aspectj.lang.ProceedingJoinPoint;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * 过滤器上下文 —— 在整条过滤器管道中传递的「唯一数据载体」。
 *
 * <p>切面在拦截请求时创建本对象，前置过滤器收集/写入数据，后置过滤器读取数据。
 * 通过统一的 {@code attributes} 扩展映射，自定义过滤器之间可以自由传递任意数据。
 *
 * @author API Governance Team
 * @since 1.0
 */
public class FilterContext {

    /** AOP 连接点（用于调用 {@code pjp.proceed()}，仅切面使用）。 */
    private final ProceedingJoinPoint joinPoint;

    /** API 唯一标识（全限定类名#方法名），同时作为限流键与指标键。 */
    private final String apiKey;

    /** 被拦截的方法。 */
    private final Method method;

    /** 目标类（已去除代理包装的真实类）。 */
    private final Class<?> targetClass;

    /** 方法入参。 */
    private final Object[] args;

    /** 请求开始时间（纳秒），用于计算耗时。 */
    private final long startTime;

    /** HTTP 方法（GET/POST 等）。 */
    private String httpMethod;

    /** 请求路径（含路径变量模式）。 */
    private String path;

    /** 限流阈值（窗口内最大请求数）。 */
    private int rateLimit = -1;

    /** 限流时间窗口（秒）。 */
    private int window = 1;

    /** 本次请求是否需要执行限流。 */
    private boolean rateLimitEnabled = false;

    /** 本次请求是否输出日志（默认开启，可由 @NoLog 关闭）。 */
    private boolean logEnabled = true;

    /** 是否已被前置过滤器拒绝（短路）。 */
    private boolean rejected = false;

    /** 拒绝响应状态码（默认 429）。 */
    private int rejectStatus = 429;

    /** 拒绝原因。 */
    private String rejectReason;

    /** 业务方法返回值（成功时）。 */
    private Object result;

    /** 业务方法异常（失败时）。 */
    private Throwable error;

    /** 扩展属性映射（自定义过滤器间传递数据）。 */
    private final Map<String, Object> attributes = new HashMap<>();

    /**
     * 构造过滤器上下文。
     *
     * @param joinPoint   AOP 连接点
     * @param apiKey      API 唯一标识
     * @param method      被拦截方法
     * @param targetClass 目标类
     * @param args        方法入参
     */
    public FilterContext(ProceedingJoinPoint joinPoint, String apiKey,
                         Method method, Class<?> targetClass, Object[] args) {
        this.joinPoint = joinPoint;
        this.apiKey = apiKey;
        this.method = method;
        this.targetClass = targetClass;
        this.args = args;
        this.startTime = System.nanoTime();
    }

    // ==================== 基础信息 ====================

    public ProceedingJoinPoint getJoinPoint() {
        return joinPoint;
    }

    public String getApiKey() {
        return apiKey;
    }

    public Method getMethod() {
        return method;
    }

    public Class<?> getTargetClass() {
        return targetClass;
    }

    public Object[] getArgs() {
        return args;
    }

    public String getHttpMethod() {
        return httpMethod;
    }

    public void setHttpMethod(String httpMethod) {
        this.httpMethod = httpMethod;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    // ==================== 限流配置 ====================

    public int getRateLimit() {
        return rateLimit;
    }

    public void setRateLimit(int rateLimit) {
        this.rateLimit = rateLimit;
    }

    public int getWindow() {
        return window;
    }

    public void setWindow(int window) {
        this.window = window;
    }

    public boolean isRateLimitEnabled() {
        return rateLimitEnabled;
    }

    public void setRateLimitEnabled(boolean rateLimitEnabled) {
        this.rateLimitEnabled = rateLimitEnabled;
    }

    // ==================== 日志配置 ====================

    public boolean isLogEnabled() {
        return logEnabled;
    }

    public void setLogEnabled(boolean logEnabled) {
        this.logEnabled = logEnabled;
    }

    // ==================== 拒绝信息 ====================

    public boolean isRejected() {
        return rejected;
    }

    public void setRejected(boolean rejected) {
        this.rejected = rejected;
    }

    public int getRejectStatus() {
        return rejectStatus;
    }

    public void setRejectStatus(int rejectStatus) {
        this.rejectStatus = rejectStatus;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public void setRejectReason(String rejectReason) {
        this.rejectReason = rejectReason;
    }

    // ==================== 结果与异常 ====================

    public Object getResult() {
        return result;
    }

    public void setResult(Object result) {
        this.result = result;
    }

    public Throwable getError() {
        return error;
    }

    public void setError(Throwable error) {
        this.error = error;
    }

    /**
     * 计算执行耗时（毫秒）。
     *
     * @return 从上下文创建到当前时刻的耗时
     */
    public long getElapsedTime() {
        return (System.nanoTime() - startTime) / 1_000_000L;
    }

    // ==================== 扩展属性 ====================

    /**
     * 设置扩展属性。
     *
     * @param key   键
     * @param value 值
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * 获取扩展属性。
     *
     * @param key 键
     * @param <T> 期望类型
     * @return 属性值
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * 获取扩展属性（带默认值）。
     *
     * @param key          键
     * @param defaultValue 默认值
     * @param <T>          期望类型
     * @return 属性值或默认值
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key, T defaultValue) {
        return (T) attributes.getOrDefault(key, defaultValue);
    }
}
