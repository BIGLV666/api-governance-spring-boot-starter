package io.github.biglv666.example;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 参数维度限流演示：@RateLimit(key = "#id") 让每个 id 拥有独立配额。
 *
 * <p>快速访问 /api/users/1 三次后第 4 次会被限流（10 秒窗口），
 * 而 /api/users/2 仍可正常访问 —— 这就是 SpEL 限流键的效果。
 */
@RestController
public class UserController {

    @GetMapping("/api/users/{id}")
    @RateLimit(limit = 3, window = 10, key = "#id")
    public Map<String, Object> get(@PathVariable Long id) {
        return Map.of("id", id, "name", "user-" + id);
    }
}
