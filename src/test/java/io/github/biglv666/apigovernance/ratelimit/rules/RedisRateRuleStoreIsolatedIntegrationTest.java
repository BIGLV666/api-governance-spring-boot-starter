package io.github.biglv666.apigovernance.ratelimit.rules;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 动态规则存储隔离集成测试 —— 上限防护与 fail-stale 故障语义。
 *
 * <p>上限用例依赖「干净库」的规则计数，使用类级内嵌实例；故障用例会停止 Redis，
 * 为避免方法执行顺序导致的相互污染，<b>自带独立内嵌实例</b>（测试方法内起停）。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRateRuleStoreIsolatedIntegrationTest {

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
    void maxRulesEnforcedAgainstCleanRemoteSize() {
        RedisRateRuleStore small = new RedisRateRuleStore(redisTemplate, 60_000, 2);
        try {
            small.put("limit.A#get", 1, 1);
            small.put("limit.B#get", 1, 1);
            assertThatThrownBy(() -> small.put("limit.C#get", 1, 1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("上限");
            // 覆盖已有 pattern 不占新名额
            small.put("limit.B#get", 5, 1);
            assertThat(small.findMatch("limit.B#get"))
                    .map(DynamicRateRule::getLimit).contains(5);
        } finally {
            small.close();
            redisTemplate.delete(RedisRateRuleStore.RULES_KEY);
            redisTemplate.delete(RedisRateRuleStore.VERSION_KEY);
        }
    }

    @Test
    void redisFailureKeepsLastSnapshotAndRejectsWrites() throws IOException {
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
            StringRedisTemplate ownTemplate = new StringRedisTemplate(factory);

            RedisRateRuleStore store = new RedisRateRuleStore(ownTemplate, 60_000, 100);
            store.put("ha.FallbackController#get", 4, 10);
            assertThat(store.findMatch("ha.FallbackController#get")).isPresent();

            ownServer.stop();
            try {
                // fail-stale：读路径保留最后一次快照，不抛异常
                assertThat(store.findMatch("ha.FallbackController#get"))
                        .map(DynamicRateRule::getLimit).contains(4);
                // 刷新路径同样 fail-stale（不抛异常，仅限频 warn）
                store.refreshForTest();
                assertThat(store.findMatch("ha.FallbackController#get")).isPresent();
                // 写路径显式失败，交由管理接口反馈
                assertThatThrownBy(() -> store.put("ha.NewController#get", 1, 1))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("写入 Redis 动态规则失败");
                assertThatThrownBy(() -> store.remove("ha.FallbackController#get"))
                        .isInstanceOf(IllegalStateException.class);
            } finally {
                store.close();
                factory.destroy();
            }
        } finally {
            if (ownServer.isActive()) {
                ownServer.stop();
            }
        }
    }
}
