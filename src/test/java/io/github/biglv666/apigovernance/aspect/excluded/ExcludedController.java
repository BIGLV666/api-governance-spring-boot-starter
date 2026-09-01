package io.github.biglv666.apigovernance.aspect.excluded;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 治理范围端到端测试固件：位于 {@code exclude-packages} 命中的包内，不应参与治理。
 *
 * @author API Governance Team
 * @since 0.4.0
 */
@RestController
@RequestMapping("/gov-excluded")
public class ExcludedController {

    @GetMapping("/ping")
    public String ping() {
        return "pong";
    }
}
