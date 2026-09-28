package io.github.biglv666.apigovernance.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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

    /**
     * 处理治理拒绝异常。
     *
     * <p>异常携带的响应头（如限流标准头 {@code RateLimit-*} / {@code Retry-After}）
     * 会原样写入响应；宿主若自定义 {@code GovernanceExceptionHandler} Bean 覆盖本类，
     * 需自行处理 {@link GovernanceException#getHeaders()}。
     *
     * @param ex 治理拒绝异常
     * @return 携带统一 JSON 结构与治理响应头的响应实体
     */
    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<Map<String, Object>> handleGovernanceException(GovernanceException ex) {
        Map<String, Object> body = buildBody(ex.getCode(), ex.getMessage(), ex.getStatus());
        return ResponseEntity.status(ex.getStatus())
                .headers(h -> ex.getHeaders().forEach(h::set))
                .body(body);
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
}
