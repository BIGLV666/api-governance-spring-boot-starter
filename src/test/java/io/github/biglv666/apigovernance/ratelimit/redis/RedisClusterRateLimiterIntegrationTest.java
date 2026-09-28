package io.github.biglv666.apigovernance.ratelimit.redis;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.data.redis.connection.RedisClusterConfiguration;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Redis 集群集成测试 —— 验证分布式限流器在真实 Redis Cluster 下的行为，
 * 重点是 {@code resetAll} 的多节点扇出（遍历 master 逐个 SCAN + 删除）。
 *
 * <p><b>背景</b>：{@code RedisTemplate#scan} 只绑定初始连接所在的节点，
 * 旧实现 {@code resetAll} 在集群下会漏掉其余 master 上的键；内嵌单机 Redis
 * 无法暴露该缺陷，必须用真实集群验证。本测试将大量 key 预置到多个 master
 * （100 个不同 key 摊到 16384 个 slot 必然跨节点），断言 resetAll 后<b>每个</b>
 * master 的 ratelimit 键全部清空。
 *
 * <h3>启动集群（单容器 3 master）</h3>
 * <pre>
 * docker run -d --name governance-redis-cluster \
 *   -p 7000:7000 -p 7001:7001 -p 7002:7002 \
 *   -p 17000:17000 -p 17001:17001 -p 17002:17002 \
 *   redis:7-alpine sh -c 'for i in 0 1 2; do mkdir -p /data/n$i; done;
 *     redis-server --port 7000 --cluster-enabled yes --cluster-config-file /data/n0/nodes.conf
 *       --cluster-node-timeout 5000 --cluster-announce-ip 127.0.0.1 --appendonly no --save "" --dir /data/n0 --daemonize yes;
 *     redis-server --port 7001 ...（同上，n1/7001）;
 *     redis-server --port 7002 ...（同上，n2/7002）;
 *     sleep infinity'
 * docker exec governance-redis-cluster \
 *   redis-cli --cluster create 127.0.0.1:7000 127.0.0.1:7001 127.0.0.1:7002 --cluster-yes
 * </pre>
 * 集群未启动时整个测试类按假设跳过，不影响其他环境的构建。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RedisClusterRateLimiterIntegrationTest {

    private static final List<Integer> CLUSTER_PORTS = List.of(7000, 7001, 7002);

    private LettuceConnectionFactory factory;

    private StringRedisTemplate redisTemplate;

    private RedisSlidingWindowRateLimiter slidingWindowLimiter;

    private RedisTokenBucketRateLimiter tokenBucketLimiter;

    @BeforeAll
    void connectToCluster() {
        try {
            RedisClusterConfiguration configuration = new RedisClusterConfiguration(
                    Set.of("127.0.0.1:7000", "127.0.0.1:7001", "127.0.0.1:7002"));
            factory = new LettuceConnectionFactory(configuration);
            factory.afterPropertiesSet();
            redisTemplate = new StringRedisTemplate(factory);
            // 集群不可达时 ping 会抛异常：按假设跳过而非让构建失败
            redisTemplate.execute((RedisCallback<String>) conn -> conn.ping());
        } catch (Exception e) {
            assumeTrue(false, "Redis 集群未启动（127.0.0.1:7000-7002，docker 命令见类注释）："
                    + e.getMessage());
            return;
        }
        slidingWindowLimiter = new RedisSlidingWindowRateLimiter(redisTemplate);
        tokenBucketLimiter = new RedisTokenBucketRateLimiter(redisTemplate);
    }

    @BeforeEach
    void cleanSlate() {
        // 方法级隔离：前序用例留下的 ratelimit 键不影响后续用例的计数断言
        slidingWindowLimiter.resetAll();
        tokenBucketLimiter.resetAll();
    }

    @AfterAll
    void shutdown() {
        if (factory != null) {
            factory.destroy();
        }
    }

    @Test
    void slidingWindowTryAcquireRejectsBeyondLimitOnCluster() {
        String key = "org.example.ClusterCtl#get";
        for (int i = 0; i < 50; i++) {
            slidingWindowLimiter.reset(key);
            assertTrue(slidingWindowLimiter.tryAcquire(key, 2, 60));
            assertTrue(slidingWindowLimiter.tryAcquire(key, 2, 60));
            // 单 key 脚本在集群下按 key 路由：第 3 次必须被拒
            assertFalse(slidingWindowLimiter.tryAcquire(key, 2, 60));
        }
    }

    @Test
    void tokenBucketTryAcquireRefillsOnCluster() {
        String key = "org.example.ClusterCtl#bucket";
        tokenBucketLimiter.reset(key);
        assertTrue(tokenBucketLimiter.tryAcquire(key, 1, 1));
        assertFalse(tokenBucketLimiter.tryAcquire(key, 1, 1));
        // 窗口（1s）过后令牌补充，恢复放行
        try {
            Thread.sleep(1100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        assertTrue(tokenBucketLimiter.tryAcquire(key, 1, 1));
    }

    @Test
    void resetAllRemovesKeysFromEveryMasterNode() {
        // 预置 100 个滑动窗口 key + 50 个令牌桶 key：不同 key 散列到不同 slot，
        // 3 个 master 上必然都持有部分键（下方断言强制 ≥2 个节点有键，防退化为单节点假验证）
        for (int i = 0; i < 100; i++) {
            assertTrue(slidingWindowLimiter.tryAcquire("org.example.Spread#w" + i, 10, 60));
        }
        for (int i = 0; i < 50; i++) {
            assertTrue(tokenBucketLimiter.tryAcquire("org.example.Spread#t" + i, 10, 60));
        }

        Map<String, Long> before = countRatelimitKeysPerMaster();
        long mastersWithKeys = before.values().stream().filter(count -> count > 0).count();
        assertTrue(mastersWithKeys >= 2,
                "预置 key 应分布在至少 2 个 master 上（实际: " + before + "），否则无法验证扇出");

        slidingWindowLimiter.resetAll();
        tokenBucketLimiter.resetAll();

        Map<String, Long> after = countRatelimitKeysPerMaster();
        after.forEach((node, count) -> assertEquals(0L, count,
                "resetAll 后 master " + node + " 仍残留 " + count + " 个 ratelimit 键（扇出未覆盖该节点）"));
    }

    /**
     * 逐 master 统计 ratelimit 前缀键数量（{@code keys(node, pattern)} 只返回该节点上的键）。
     * 与生产代码一致：从工厂取原始集群连接（模板 execute 的装饰代理不暴露集群接口）。
     */
    private Map<String, Long> countRatelimitKeysPerMaster() {
        RedisClusterConnection cluster = (RedisClusterConnection) factory.getConnection();
        try {
            Map<String, Long> perMaster = new LinkedHashMap<>();
            for (RedisClusterNode node : cluster.clusterGetNodes()) {
                if (node.isMaster()) {
                    perMaster.put(node.getHost() + ":" + node.getPort(),
                            (long) cluster.keys(node, "ratelimit:*".getBytes(StandardCharsets.UTF_8)).size());
                }
            }
            return perMaster;
        } finally {
            cluster.close();
        }
    }
}
