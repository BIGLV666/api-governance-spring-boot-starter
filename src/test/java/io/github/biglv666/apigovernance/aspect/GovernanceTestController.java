package io.github.biglv666.apigovernance.aspect;

import io.github.biglv666.apigovernance.annotation.RateLimit;
import io.github.biglv666.apigovernance.annotation.Skip;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 治理切面端到端测试的 Controller 固件。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
@RestController
@RequestMapping("/gov-test")
public class GovernanceTestController {

    @GetMapping("/limited")
    @RateLimit(limit = 2, window = 60)
    public String limited() {
        return "ok";
    }

    @GetMapping("/skip")
    @Skip
    public String skip() {
        return "skipped";
    }

    @GetMapping("/boom")
    public String boom() {
        // IllegalArgumentException 由内置 GovernanceExceptionHandler 转为 400
        throw new IllegalArgumentException("boom");
    }

    @GetMapping("/ip")
    public String ip() {
        return "ok";
    }
}
