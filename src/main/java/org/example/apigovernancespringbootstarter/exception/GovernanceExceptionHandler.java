package org.example.apigovernancespringbootstarter.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 治理异常全局处理器。
 *
 * <p>捕获 {@link GovernanceException} 并转换为统一结构的标准 JSON 响应，例如限流拒绝：
 * <pre>
 * {
 *   "success": false,
 *   "code": "RATE_LIMITED",
 *   "message": "请求过于频繁，请稍后重试",
 *   "status": 429,
 *   "timestamp": "2024-01-01T00:00:00Z"
 * }
 * </pre>
 *
 * <h3>维护说明</h3>
 * <p>如需调整响应结构，只需修改 {@link #buildBody}；如需支持更多异常类型，
 * 新增对应的 {@code @ExceptionHandler} 方法即可。
 *
 * @author API Governance Team
 * @since 1.0
 */
@RestControllerAdvice
public class GovernanceExceptionHandler {

    /**
     * 处理治理拒绝异常。
     *
     * @param ex 治理拒绝异常
     * @return 携带统一 JSON 结构的响应实体
     */
    @ExceptionHandler(GovernanceException.class)
    public ResponseEntity<Map<String, Object>> handleGovernanceException(GovernanceException ex) {
        Map<String, Object> body = buildBody(ex.getCode(), ex.getMessage(), ex.getStatus());
        return ResponseEntity.status(ex.getStatus()).body(body);
    }

    /**
     * 兜底处理：若控制器以 {@link IllegalArgumentException} 等形式抛出，
     * 也可在这里转换为 400，避免治理管道之外的非预期异常裸露给前端。
     *
     * @param ex 非法参数异常
     * @return 400 响应
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        Map<String, Object> body = buildBody("BAD_REQUEST", ex.getMessage(),
                HttpStatus.BAD_REQUEST.value());
        return ResponseEntity.badRequest().body(body);
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
