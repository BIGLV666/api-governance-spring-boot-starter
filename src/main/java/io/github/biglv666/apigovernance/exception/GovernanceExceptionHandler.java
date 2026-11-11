package io.github.biglv666.apigovernance.exception;

import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 治理拒绝异常的专用处理器。
 *
 * <p><b>职责边界</b>：只处理框架自有类型 {@link GovernanceException}（前置过滤器链短路的
 * 统一出口），并转换为统一结构的标准 JSON 响应，例如限流拒绝：
 * <pre>
 * {
 *   "success": false,
 *   "code": "REJECTED",
 *   "message": "请求过于频繁，请稍后重试",
 *   "status": 429,
 *   "timestamp": "2024-01-01T00:00:00Z"
 * }
 * </pre>
 *
 * <p>刻意<b>不</b>兜底宿主应用的任何异常（如 {@code IllegalArgumentException}）：
 * Starter 注册的全局 advice 会静默改写宿主的异常处理行为（500 变 400、抢占宿主
 * 自定义 advice），属于框架越界。宿主异常一律交还宿主自身体系处理。
 *
 * <h3>维护说明</h3>
 * <p>如需调整响应结构，只需修改 {@link #buildBody}。
 *
 * @author API Governance Team
 * @since 1.0
 */
@RestControllerAdvice
public class GovernanceExceptionHandler {

    private final ApiGovernanceProperties properties;

    /**
     * 构造处理器（默认不启用 RFC 9457，保持 0.8.0 响应结构）。
     */
    public GovernanceExceptionHandler() {
        this(null);
    }

    /**
     * 构造处理器。
     *
     * @param properties 全局配置（可为 null，按默认结构输出）
     */
    public GovernanceExceptionHandler(ApiGovernanceProperties properties) {
        this.properties = properties;
    }

    /**
     * 处理治理拒绝异常。
     *
     * <p>异常携带的响应头（如限流标准头 {@code RateLimit-*} / {@code Retry-After}）
     * 会原样写入响应；宿主若自定义 {@code GovernanceExceptionHandler} Bean 覆盖本类，
     * 需自行处理 {@link GovernanceException#getHeaders()}。
     *
     * <p>{@code api.governance.problem-details.enabled=true} 时按 RFC 9457 输出
     * {@code application/problem+json}；默认关闭，保持原有响应结构。
     *
     * @param ex 治理拒绝异常
     * @return 携带统一 JSON 结构与治理响应头的响应实体
     */
    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<Map<String, Object>> handleGovernanceException(GovernanceException ex) {
        HttpServletRequest request = currentRequest();
        boolean problemDetails = properties != null
                && properties.getProblemDetails().isEnabled();
        Map<String, Object> body = problemDetails
                ? buildProblemBody(ex, request) : buildBody(ex.getCode(), ex.getMessage(), ex.getStatus());
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ex.getStatus())
                .headers(h -> ex.getHeaders().forEach(h::set));
        if (problemDetails) {
            builder.contentType(MediaType.parseMediaType("application/problem+json"));
        }
        return builder.body(body);
    }

    /**
     * 构建统一响应体。
     *
     * @param code    业务错误码
     * @param message 错误信息
     * @param status  HTTP 状态码
     * @return 有序的响应 Map（保证字段顺序稳定，便于阅读）
     */
    private Map<String, Object> buildBody(String code, String message, int status) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", code);
        body.put("message", message);
        body.put("status", status);
        body.put("timestamp", Instant.now().toString());
        return body;
    }

    /**
     * 构建 RFC 9457 Problem Details 响应体。
     *
     * <p>核心字段：{@code type}（默认 {@code about:blank}）、{@code title}、
     * {@code status}、{@code detail}、{@code instance}；另保留治理扩展字段
     * {@code code} 与 {@code timestamp}，便于既有工具过渡。
     *
     * @param ex      治理拒绝异常
     * @param request 当前请求
     * @return problem+json 响应体
     */
    private static HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes servletAttributes
                ? servletAttributes.getRequest() : null;
    }

    private Map<String, Object> buildProblemBody(GovernanceException ex,
                                                 HttpServletRequest request) {
        HttpStatus httpStatus = HttpStatus.resolve(ex.getStatus());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", httpStatus != null ? httpStatus.getReasonPhrase() : "Governance Rejected");
        body.put("status", ex.getStatus());
        body.put("detail", ex.getMessage());
        body.put("instance", request != null ? request.getRequestURI() : null);
        body.put("code", ex.getCode());
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
