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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 动态规则存储集成测试 —— 使用内嵌 Redis 验证版本号同步与
 * 多节点（多 store 实例）传播。
 *
 * <p>上限防护与 fail-stale 故障语义在独立的
 * {@code RedisRateRuleStoreIsolatedIntegrationTest} 中验证（需要独占的
 * Redis 实例，避免与本类共享数据相互干扰）。
 *
 * <p>环境不具备内嵌 Redis 启动条件时，整个测试类按假设跳过。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisRateRuleStoreIntegrationTest {

    private RedisServer redisServer;

    private StringRedisTemplate redisTemplate;

    private RedisRateRuleStore nodeA;

    private RedisRateRuleStore nodeB;

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
            return;
        }
        LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", port);
        factory.afterPropertiesSet();
        redisTemplate = new StringRedisTemplate(factory);
        // 轮询间隔拉大到不影响断言节奏：同步通过 refreshForTest 显式触发
        nodeA = new RedisRateRuleStore(redisTemplate, 60_000, 100);
        nodeB = new RedisRateRuleStore(redisTemplate, 60_000, 100);
    }

    @AfterAll
    void stopEmbeddedRedis() throws IOException {
        if (nodeA != null) {
            nodeA.close();
        }
        if (nodeB != null) {
            nodeB.close();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
    }

    @Test
    void putVisibleLocallyImmediately() {
        nodeA.put("com.x.UserController#get", 5, 60);

        Optional<DynamicRateRule> match = nodeA.findMatch("com.x.UserController#get");
        assertThat(match).isPresent();
        assertThat(match.get().getLimit()).isEqualTo(5);
        assertThat(match.get().getWindow()).isEqualTo(60);
    }

    @Test
    void rulesPropagateToOtherNodeAfterVersionBump() {
        nodeA.put("com.x.OrderController#create", 3, 30);

        // 另一节点（模拟集群其他实例）尚未轮询到新版本：快照仍旧
        assertThat(nodeB.findMatch("com.x.OrderController#create")).isEmpty();
        // 版本变化后刷新即看到（轮询间隔被设为 60s，这里手动触发等价路径）
        nodeB.refreshForTest();
        assertThat(nodeB.findMatch("com.x.OrderController#create"))
                .map(DynamicRateRule::getLimit).contains(3);
    }

    @Test
    void removePropagatesAndIsIdempotent() {
        nodeA.put("com.x.CartController#add", 2, 10);
        nodeB.refreshForTest();
        assertThat(nodeB.findMatch("com.x.CartController#add")).isPresent();

        nodeA.remove("com.x.CartController#add");
        assertThat(nodeA.findMatch("com.x.CartController#add")).isEmpty();

        nodeB.refreshForTest();
        assertThat(nodeB.findMatch("com.x.CartController#add")).isEmpty();

        // 幂等：删除不存在的 pattern 不报错、不影响版本同步
        nodeA.remove("com.x.CartController#add");
    }

    @Test
    void prefixAndExactRulePriorityAcrossNodes() {
        nodeA.put("com.x.Promo*", 100, 1);
        nodeA.put("com.x.PromoController#flash", 1, 1);
        nodeB.refreshForTest();

        assertThat(nodeB.findMatch("com.x.PromoController#flash"))
                .map(DynamicRateRule::getLimit).contains(1);
        assertThat(nodeB.findMatch("com.x.PromoController#list"))
                .map(DynamicRateRule::getLimit).contains(100);
    }

    @Test
    void corruptValueIsSkippedNotFatal() {
        // 直接向 Redis 写一条格式损坏的规则，模拟跨版本残留 / 外部误写
        redisTemplate.opsForHash().put(RedisRateRuleStore.RULES_KEY,
                "corrupt.BrokenController#get", "not-a-number");
        nodeA.put("corrupt.OkController#get", 1, 1);
        nodeA.refreshForTest();
        // 刷新后：损坏条目被跳过，合法条目正常生效
        assertThat(nodeA.findMatch("corrupt.OkController#get")).isPresent();
        assertThat(nodeA.findMatch("corrupt.BrokenController#get")).isEmpty();
        // 清理，避免影响后续用例的规则视图
        redisTemplate.opsForHash().delete(RedisRateRuleStore.RULES_KEY,
                "corrupt.BrokenController#get", "corrupt.OkController#get");
        nodeA.refreshForTest();
    }
}
