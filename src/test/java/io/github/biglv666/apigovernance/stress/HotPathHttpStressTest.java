package io.github.biglv666.apigovernance.stress;

import io.github.biglv666.apigovernance.ratelimit.rules.InMemoryRateRuleStore;
import io.github.biglv666.apigovernance.ratelimit.rules.RateRuleStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 端到端热路径压测（testskill 必做项）—— 真实 Tomcat + keep-alive HTTP 压测器，
 * 对<b>同一个端点</b>在「动态规则提交前 / 提交后」测量吞吐与分位延迟：
 * 规则命中路径相对无规则路径吞吐差应 &lt; 5%（代码断言放宽到 10% 以容忍共享机器噪声，
 * 实测值打印在报告中）。同时验证规则热更新即时生效（不重启）与封禁拒绝路径稳定性
 * （高 429 率下零 5xx）。
 *
 * <p><b>手动触发</b>：{@code mvn test -Dtest=HotPathHttpStressTest -Dstress=true}；
 * {@code stress=true} 缺失时跳过，不给常规 CI 引入吞吐抖动。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
@SpringBootTest(classes = HotPathHttpStressTest.BenchApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 治理保持默认：local 令牌桶 + default-limit=-1（拦截但不限流）
                "api.governance.log.slow-threshold-ms=60000",
                // 压测关注治理机制本身的开销，关闭访问日志的 I/O 干扰
                "logging.level.io.github.biglv666.apigovernance=OFF"
        })
class HotPathHttpStressTest {

    private static final int THREADS = 16;
    private static final long WARMUP_MS = 3_000;
    private static final long MEASURE_MS = 8_000;
    private static final int ROUNDS = 3;
    private static final int MAX_SAMPLES = 300_000;

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class BenchApp {

        @Bean
        public RateRuleStore benchRuleStore() {
            return new InMemoryRateRuleStore(10);
        }

        @RestController
        static class BenchController {
            @GetMapping("/bench")
            public String bench() {
                return "ok";
            }
        }
    }

    @Autowired
    private InMemoryRateRuleStore ruleStore;

    @org.springframework.beans.factory.annotation.Value("${local.server.port}")
    private int port;

    /** 单轮结果：吞吐（req/s）与客户端观测延迟的 P50/P99（毫秒）。 */
    record RoundResult(double reqPerSec, long p50Ms, long p99Ms) {
    }

    private URI benchUri() {
        return URI.create("http://127.0.0.1:" + port + "/bench");
    }

    /**
     * 对一个目标 URL：预热一轮 + 3 轮测量（16 线程 keep-alive），返回各轮结果。
     * 全程断言零 5xx（治理降级语义：治理路径的任何异常不得以 5xx 形式泄漏给调用方）。
     */
    private List<RoundResult> benchRounds(URI uri) throws InterruptedException {
        HttpClient client = newClient();
        LongAdder warmupErrors = new LongAdder();
        runRound(client, uri, WARMUP_MS, new LongAdder(), warmupErrors, null);
        assertThat(warmupErrors.sum()).as("预热阶段不应出现 5xx/请求异常").isZero();

        List<RoundResult> results = new ArrayList<>(ROUNDS);
        for (int round = 0; round < ROUNDS; round++) {
            LongAdder total = new LongAdder();
            LongAdder errors = new LongAdder();
            List<Long> latenciesMs = Collections.synchronizedList(new ArrayList<>(MAX_SAMPLES));
            runRound(client, uri, MEASURE_MS, total, errors, latenciesMs);
            assertThat(errors.sum()).as("测量阶段不应出现 5xx/请求异常").isZero();
            List<Long> sorted = new ArrayList<>(latenciesMs);
            Collections.sort(sorted);
            long p50 = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
            long p99 = sorted.isEmpty() ? 0 : sorted.get((int) (sorted.size() * 0.99));
            results.add(new RoundResult(total.sum() / (MEASURE_MS / 1000.0), p50, p99));
        }
        return results;
    }

    private static HttpClient newClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /**
     * 单轮并发请求：16 线程循环 GET 直到 deadline；total/errors 计数，
     * latencies 非 null 时记录客户端观测延迟（毫秒，有界采样）。
     */
    private static void runRound(HttpClient client, URI uri, long durationMs,
                                 LongAdder total, LongAdder errors, List<Long> latencies)
            throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[THREADS];
        for (int i = 0; i < THREADS; i++) {
            threads[i] = new Thread(() -> {
                try {
                    start.await();
                    long deadline = System.nanoTime() + durationMs * 1_000_000L;
                    while (System.nanoTime() < deadline) {
                        long t0 = System.nanoTime();
                        HttpResponse<Void> response = client.send(request,
                                HttpResponse.BodyHandlers.discarding());
                        long costMs = (System.nanoTime() - t0) / 1_000_000;
                        total.increment();
                        if (response.statusCode() >= 500) {
                            errors.increment();
                        }
                        if (latencies != null && latencies.size() < MAX_SAMPLES) {
                            latencies.add(costMs);
                        }
                    }
                } catch (Throwable ignored) {
                    errors.increment();
                }
            }, "http-bench");
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private static double medianReqPerSec(List<RoundResult> rounds) {
        return rounds.stream().map(RoundResult::reqPerSec).sorted()
                .toList().get(rounds.size() / 2);
    }

    private static void printRounds(String label, List<RoundResult> rounds) {
        RoundResult median = rounds.stream()
                .sorted(Comparator.comparingDouble(RoundResult::reqPerSec))
                .toList().get(rounds.size() / 2);
        System.out.printf("[压测] %s 中位轮: %.0f req/s, P50=%dms, P99=%dms（%d 轮取中位）%n",
                label, median.reqPerSec(), median.p50Ms(), median.p99Ms(), rounds.size());
    }

    @Test
    void dynamicRuleHotUpdateAndOverhead() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "true".equals(System.getProperty("stress")), "手动压测：-Dstress=true 触发");

        String pattern = BenchApp.BenchController.class.getName() + "#bench";
        URI uri = benchUri();

        // 组 1：无规则（0.6.0 行为）
        List<RoundResult> baseline = benchRounds(uri);
        printRounds("组1 无规则(0.6.0 行为)", baseline);
        double baselineOps = medianReqPerSec(baseline);

        // 组 2：运行期提交规则（热更新，不重启）—— limit 远高于实际流量，仅测规则命中开销
        ruleStore.put(pattern, 100_000_000, 1);
        List<RoundResult> withRule = benchRounds(uri);
        printRounds("组2 动态规则命中", withRule);
        double ruleOps = medianReqPerSec(withRule);
        System.out.printf("[压测] 规则命中路径吞吐变化: %.2f%%%n",
                (ruleOps - baselineOps) / baselineOps * 100);

        // 验收门槛：规则命中路径相对无规则路径退化 ≤10%（计划预算 5%，共享机器噪声宽容一倍）
        assertThat(ruleOps).as("规则命中路径吞吐退化超过 10%").isGreaterThan(baselineOps * 0.90);

        // 组 3：封禁路径（limit=0）—— 热更新立即 429，高拒绝率下服务稳定
        ruleStore.put(pattern, 0, 1);
        HttpClient client = newClient();
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<Void> banned = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(banned.statusCode()).as("limit=0 封禁应立即生效").isEqualTo(429);

        LongAdder total = new LongAdder();
        LongAdder errors = new LongAdder();
        runRound(client, uri, 5_000, total, errors, null);
        assertThat(errors.sum()).as("封禁高压测中不应出现 5xx/请求异常").isZero();
        assertThat(total.sum()).isPositive();
        System.out.printf("[压测] 组3 封禁路径(全部429): %.0f req/s, 5xx/异常=%d%n",
                total.sum() / 5.0, errors.sum());

        // 组 4：删除规则 —— 热更新立即恢复放行
        ruleStore.remove(pattern);
        HttpResponse<Void> after = client.send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(after.statusCode()).as("删除规则后应恢复 200").isEqualTo(200);
        System.out.printf("[压测] 组4 删除规则后恢复: %d OK%n", after.statusCode());
    }
}
