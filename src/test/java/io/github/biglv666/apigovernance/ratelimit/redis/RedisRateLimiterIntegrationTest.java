package io.github.biglv666.apigovernance.ratelimit.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 限流器集成测试 —— 使用内嵌 Redis 验证 Lua 脚本的正确性与并发准确性。
 *
 * <p>环境不具备内嵌 Redis 启动条件（如缺少可执行文件）时，整个测试类按假设跳过，
 * 不影响其他平台的构建。
 *
 * @author API Governance Team
 * @since 0.3.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRateLimiterIntegrationTest {

    private RedisServer redisServer;

    private StringRedisTemplate redisTemplate;

    @BeforeAll
    void startEmbeddedRedis() throws IOException {
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try {
            redisServer = new RedisServer(port);
            redisServer.start();
        } catch (Exception e) {
            // 内嵌 Redis 不可用（缺可执行文件等）：跳过集成测试而非让构建失败
            assumeTrue(false, "embedded redis unavailable: " + e.getMessage());
            return;
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
    }

    @AfterAll
    void stopEmbeddedRedis() throws IOException {
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @Test
    void slidingWindowRejectsAfterLimit() {
        RedisSlidingWindowRateLimiter limiter = new RedisSlidingWindowRateLimiter(redisTemplate);
        String key = "test:sw:" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, 5, 5)).as("第 %d 次应放行", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire(key, 5, 5)).isFalse();
        // 计数与拒绝请求不叠加：窗口内仍是 5 条记录
        assertThat(limiter.getCurrentCount(key)).isEqualTo(5);
    }

    @Test
    void slidingWindowCountsConcurrentRequestsExactly() throws Exception {
        RedisSlidingWindowRateLimiter limiter = new RedisSlidingWindowRateLimiter(redisTemplate);
        String key = "test:sw-concurrent:" + System.nanoTime();
        int limit = 50;
        int totalRequests = 100;

        AtomicInteger allowed = new AtomicInteger();
        runConcurrently(totalRequests, 10, () -> {
            if (limiter.tryAcquire(key, limit, 5)) {
                allowed.incrementAndGet();
            }
        });
        // Lua 原子化保证并发下放行数精确等于阈值（member 唯一，无覆盖少计数）
        assertThat(allowed.get()).isEqualTo(limit);
    }

    @Test
    void slidingWindowResetAllClearsWindows() {
        RedisSlidingWindowRateLimiter limiter = new RedisSlidingWindowRateLimiter(redisTemplate);
        String key = "test:sw-reset:" + System.nanoTime();
        assertThat(limiter.tryAcquire(key, 1, 5)).isTrue();
        assertThat(limiter.tryAcquire(key, 1, 5)).isFalse();

        limiter.resetAll();
        assertThat(limiter.tryAcquire(key, 1, 5)).isTrue();
    }

    @Test
    void tokenBucketRejectsWhenDrained() {
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redisTemplate);
        String key = "test:tb:" + System.nanoTime();

        for (int i = 0; i < 5; i++) {
            assertThat(limiter.tryAcquire(key, 5, 10)).as("第 %d 个令牌应可用", i + 1).isTrue();
        }
        // 桶已耗尽且补充速率 0.5/秒，立即再取必然拒绝
        assertThat(limiter.tryAcquire(key, 5, 10)).isFalse();
    }

    @Test
    void tokenBucketResetAllClearsBuckets() {
        RedisTokenBucketRateLimiter limiter = new RedisTokenBucketRateLimiter(redisTemplate);
        String key = "test:tb-reset:" + System.nanoTime();
        assertThat(limiter.tryAcquire(key, 1, 10)).isTrue();
        assertThat(limiter.tryAcquire(key, 1, 10)).isFalse();

        limiter.resetAll();
        assertThat(limiter.tryAcquire(key, 1, 10)).isTrue();
    }

    /** 多线程并发执行同一任务，等待全部完成并透传中断/执行异常。 */
    private void runConcurrently(int totalRequests, int threads, Runnable task) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < totalRequests; i++) {
                futures.add(executor.submit(task));
            }
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
