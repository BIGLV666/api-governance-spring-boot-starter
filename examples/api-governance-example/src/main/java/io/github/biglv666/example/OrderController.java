package io.github.biglv666.example;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import io.github.biglv666.apigovernance.annotation.Skip;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 类级限流与放行注解演示：OrderController 全部接口共享 5 次/10 秒配额，
 * 健康检查接口用 @Skip 完全绕过治理（不限流、不统计、不记日志）。
 */
@RestController
@RateLimit(limit = 5, window = 10)
public class OrderController {

    @GetMapping("/api/orders")
    public Map<String, Object> list() {
        return Map.of("orders", 0);
    }

    @GetMapping("/api/health")
    @Skip(reason = "健康检查完全放行")
    public String health() {
        return "UP";
    }
}
