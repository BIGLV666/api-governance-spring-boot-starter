package io.github.biglv666.example;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 慢方法演示：耗时 1.5 秒，超过 application.yml 中配置的 1 秒阈值，
 * 触发慢方法日志、慢方法指标与 SLOW_METHOD 告警（控制台 [ALERT] 输出）。
 */
@RestController
public class SlowController {

    @GetMapping("/api/slow")
    public Map<String, Object> slow() throws InterruptedException {
        Thread.sleep(1500);
        return Map.of("status", "done", "elapsed", "1500ms");
    }
}
