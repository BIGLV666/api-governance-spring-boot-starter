package io.github.biglv666.example;

import io.github.biglv666.apigovernance.async.annotation.AsyncAction;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 线程池饱和演示：命中即可触发一个「耗时旁路任务」（AFTER_SUCCESS Handler 固定耗时 500ms），
 * 用于复现队列拒绝与 {@code ASYNC_TASK_REJECTED} 告警。
 *
 * <h3>复现步骤（故障演练）</h3>
 * <pre>
 * # 以 1 线程 + 1 队列的极小池启动（默认 2/8/1000 不会饱和）：
 * mvn spring-boot:run -Dspring-boot.run.arguments="--server.port=8081 \
 *   --api.governance.async.core-pool-size=1 --api.governance.async.max-pool-size=1 \
 *   --api.governance.async.queue-capacity=1"
 * # 并发打 6 次：1 个执行 + 1 个排队 + 4 个被拒绝 → 控制台 [ALERT] ASYNC_TASK_REJECTED
 * for i in $(seq 1 6); do curl -s http://localhost:8081/api/heavy & done; wait
 * </pre>
 *
 * <p>业务方法本身不等待旁路任务，请求全部快速返回（这正是异步旁路的语义）。
 */
@RestController
public class AsyncSaturationDemo {

    private final HeavyService heavyService;

    AsyncSaturationDemo(HeavyService heavyService) {
        this.heavyService = heavyService;
    }

    /**
     * 线程池饱和演示端点：业务立即返回，旁路任务固定耗时 500ms。
     */
    @GetMapping("/api/heavy")
    public Map<String, Object> heavy() {
        String result = heavyService.run();
        return Map.of("status", "accepted", "biz", result,
                "note", "旁路任务耗时 500ms，线程池小时会触发队列拒绝");
    }

    /**
     * 演示服务：{@code demo.heavy} 动作的旁路任务在 AFTER_SUCCESS 阶段阻塞 500ms。
     */
    @Service
    public static class HeavyService {

        private static final Logger log = LoggerFactory.getLogger(HeavyService.class);

        /**
         * 饱和演示动作（业务零耗时）。
         *
         * @return 固定结果
         */
        @AsyncAction("demo.heavy")
        public String run() {
            return "ok";
        }
    }

    /**
     * 饱和演示处理器：模拟耗时旁路任务（写库、外部调用等慢 IO 的替身）。
     */
    @Component
    public static class HeavyHandlers {

        private static final Logger log = LoggerFactory.getLogger(HeavyHandlers.class);

        /**
         * 固定睡眠 500ms，制造线程池占用以复现队列拒绝。
         *
         * @param event 异步事件快照
         */
        @AsyncHandler(value = "demo.heavy", phase = AsyncPhase.AFTER_SUCCESS, order = 100)
        public void slowSideTask(AsyncEvent event) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            log.info("[异步] 耗时旁路任务完成 - eventId: {}", event.id());
        }
    }
}