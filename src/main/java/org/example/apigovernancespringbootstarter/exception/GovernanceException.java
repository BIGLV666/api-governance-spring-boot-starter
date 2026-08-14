package org.example.apigovernancespringbootstarter.exception;

/**
 * 治理拒绝异常。
 *
 * <p>当前置过滤器链短路（例如限流拒绝、参数校验失败）时，切面抛出本异常，
 * 由 {@link GovernanceExceptionHandler} 统一转换为标准 JSON 响应。
 *
 * <p>采用「异常 + 全局异常处理器」而非直接写 {@code HttpServletResponse} 的方式，原因：
 * <ul>
 *   <li>不依赖 Servlet API，保持 Starter 轻量；</li>
 *   <li>与宿主应用的返回类型无关，避免切面返回值与 Controller 声明类型不匹配的问题；</li>
 *   <li>统一、可扩展的响应格式。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class GovernanceException extends RuntimeException {

    /**
     * HTTP 状态码，默认 429（Too Many Requests）。
     */
    private final int status;

    /**
     * 业务错误码，便于管理工具/前端区分拒绝原因。
     */
    private final String code;

    /**
     * 构造治理拒绝异常。
     *
     * @param status  HTTP 状态码
     * @param code    业务错误码
     * @param message 拒绝原因（对用户友好）
     */
    public GovernanceException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * 构造治理拒绝异常（使用默认状态码 429）。
     *
     * @param code    业务错误码
     * @param message 拒绝原因
     */
    public GovernanceException(String code, String message) {
        this(429, code, message);
    }

    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
