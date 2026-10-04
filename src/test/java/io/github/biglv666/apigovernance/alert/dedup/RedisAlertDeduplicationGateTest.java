package io.github.biglv666.apigovernance.alert.dedup;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 集群告警去重闸门集成测试：SETNX 抢占语义、TTL 过期释放与故障 fail-open。
 *
 * <p>环境不具备内嵌 Redis 启动条件时，整个测试类按假设跳过。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisAlertDeduplicationGateTest {

    private RedisServer redisServer;

    private StringRedisTemplate redisTemplate;

    private int port;

    @BeforeAll
    void startEmbeddedRedis() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        try {
            redisServer = new RedisServer(port);
            redisServer.start();
        } catch (Exception e) {
            assumeTrue(false, "embedded redis unavailable: " + e.getMessage());
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
    }

    @AfterAll
    void stopEmbeddedRedis() throws IOException {
        if (redisServer != null && redisServer.isActive()) {
            redisServer.stop();
        }
    }

    @Test
    void secondAcquirerIsRejectedUntilTtlExpires() throws Exception {
        RedisAlertDeduplicationGate nodeA = new RedisAlertDeduplicationGate(redisTemplate);
        RedisAlertDeduplicationGate nodeB = new RedisAlertDeduplicationGate(redisTemplate);

        assertThat(nodeA.tryAcquire("SLOW_METHOD|api", 1000)).isTrue();
        assertThat(nodeB.tryAcquire("SLOW_METHOD|api", 1000)).isFalse();
        // 集群语义：任意实例（包括抢到者自己）在窗口内都被挡下
        assertThat(nodeA.tryAcquire("SLOW_METHOD|api", 1000)).isFalse();

        Thread.sleep(1100);
        assertThat(nodeB.tryAcquire("SLOW_METHOD|api", 1000)).isTrue();
    }

    @Test
    void distinctKeysAreIndependent() {
        RedisAlertDeduplicationGate gate = new RedisAlertDeduplicationGate(redisTemplate);
        assertThat(gate.tryAcquire("SLOW_METHOD|api-a", 1000)).isTrue();
        assertThat(gate.tryAcquire("SLOW_METHOD|api-b", 1000)).isTrue();
    }

    @Test
    void redisFailureIsFailOpen() throws IOException {
        // 独立实例：本用例起自己的内嵌 Redis 并在用例内停止，不影响类级实例与其他用例
        int ownPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            ownPort = socket.getLocalPort();
        }
        RedisServer ownServer = new RedisServer(ownPort);
        ownServer.start();
        try {
            LettuceConnectionFactory factory =
                    new LettuceConnectionFactory("127.0.0.1", ownPort);
            factory.afterPropertiesSet();
            RedisAlertDeduplicationGate gate = new RedisAlertDeduplicationGate(
                    new StringRedisTemplate(factory));
            assertThat(gate.tryAcquire("fail|api", 1000)).isTrue();

            ownServer.stop();
            // fail-open：存储故障视为抢到分发权，回退单机行为，绝不丢告警
            assertThat(gate.tryAcquire("fail|api", 1000)).isTrue();
        } finally {
            if (ownServer.isActive()) {
                ownServer.stop();
            }
        }
    }
}
