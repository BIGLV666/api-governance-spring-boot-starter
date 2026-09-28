package io.github.biglv666.apigovernance.ratelimit.redis;

import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Redis 令牌桶限流器（分布式）。
 *
 * <p>「Redis 只封装」：本类不实现任何业务逻辑，仅通过 Lua 脚本原子化地封装 Redis 操作，
 * 使多实例共享同一份限流状态。
 *
 * <h3>Redis 数据结构</h3>
 * <pre>
 * key: ratelimit:token:{apiKey}
 * type: Hash
 * fields:
 *   tokens          -> 当前令牌数
 *   lastRefillTime  -> 上次补充时间戳（毫秒）
 * </pre>
 *
 * <h3>Lua 脚本逻辑</h3>
 * <ol>
 *   <li>读取桶内令牌数与上次补充时间；</li>
 *   <li>按 {@code 速率 = 容量 / 窗口} 补充令牌；</li>
 *   <li>令牌足够则扣减 1 并返回 1，否则返回 0；</li>
 *   <li>设置过期时间，避免僵尸 key 堆积。</li>
 * </ol>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class RedisTokenBucketRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenBucketRateLimiter.class);

    /** Redis key 前缀。 */
    private static final String KEY_PREFIX = "ratelimit:token:";

    /** SCAN 每批扫描/删除的 key 数量。 */
    private static final int SCAN_BATCH_SIZE = 500;

    /**
     * 令牌桶 Lua 脚本。
     * <p>KEYS[1]=key；ARGV[1]=容量(limit)；ARGV[2]=窗口(秒)。
     * <p>当前时间取自 Redis 服务器（{@code TIME} 命令，秒+微秒）：多实例部署时应用节点
     * 时钟漂移不再影响补充速率。{@code TIME} 属非确定性命令，脚本内先显式
     * {@code redis.replicate_commands()} 切换为效果复制，兼容 Redis 3.2–4.x
     * （逐字复制模式下「非确定性命令后写命令」会被拒绝）；Redis 5+ 默认即效果复制，
     * 该调用为幂等无害。
     * <p>返回 1 表示放行，0 表示拒绝。
     */
    private static final String LUA_SCRIPT =
            "local key = KEYS[1]\n" +
            "local capacity = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2])\n" +
            "local rate = capacity / window\n" +
            "\n" +
            "if redis.replicate_commands then redis.replicate_commands() end\n" +
            "local t = redis.call('TIME')\n" +
            "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)\n" +
            "\n" +
            "local bucket = redis.call('hmget', key, 'tokens', 'lastRefillTime')\n" +
            "local tokens = tonumber(bucket[1])\n" +
            "local lastRefillTime = tonumber(bucket[2])\n" +
            "\n" +
            "if tokens == nil then\n" +
            "  tokens = capacity\n" +
            "  lastRefillTime = now\n" +
            "end\n" +
            "\n" +
            "local elapsed = math.max(0, now - lastRefillTime)\n" +
            "tokens = math.min(capacity, tokens + (elapsed / 1000.0) * rate)\n" +
            "\n" +
            "if tokens >= 1 then\n" +
            "  tokens = tokens - 1\n" +
            "  redis.call('hset', key, 'tokens', tokens, 'lastRefillTime', now)\n" +
            "  redis.call('expire', key, math.max(60, window * 2))\n" +
            "  return 1\n" +
            "else\n" +
            "  redis.call('hset', key, 'tokens', tokens, 'lastRefillTime', now)\n" +
            "  redis.call('expire', key, math.max(60, window * 2))\n" +
            "  return 0\n" +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    /**
     * 构造 Redis 令牌桶限流器。
     *
     * @param redisTemplate Redis 模板（由 Spring 自动装配）
     */
    public RedisTokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = RedisScript.of(LUA_SCRIPT, Long.class);
        log.info("初始化 Redis 令牌桶限流器");
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        // 异常不在此处吞掉：由自动配置包装的 FailSafeRateLimiter 统一按
        // fail-strategy（open=放行 / close=拒绝）处理降级与告警
        // 桶补充时间由 Lua 内的 Redis TIME 提供，应用时钟漂移不影响判定
        List<String> keys = Collections.singletonList(KEY_PREFIX + key);
        Long result = redisTemplate.execute(
                rateLimitScript,
                keys,
                String.valueOf(limit),
                String.valueOf(Math.max(1, windowSeconds))
        );
        return result != null && result == 1L;
    }

    @Override
    public String getName() {
        return "token-bucket-redis";
    }

    @Override
    public long getCurrentCount(String key) {
        try {
            Object tokens = redisTemplate.opsForHash().get(KEY_PREFIX + key, "tokens");
            return tokens != null ? Double.valueOf(tokens.toString()).longValue() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void reset(String key) {
        redisTemplate.delete(KEY_PREFIX + key);
        log.debug("重置 Redis 令牌桶 - key: {}", key);
    }

    @Override
    public void resetAll() {
        // 使用 SCAN 游标分批匹配，避免 KEYS 在 key 规模大时阻塞 Redis
        scanAndDeleteAll();
        log.warn("清空所有 Redis 令牌桶");
    }

    /**
     * SCAN 游标遍历并删除本限流器前缀下的全部 key。
     * 每批 {@value #SCAN_BATCH_SIZE} 条，删除也分批提交，控制单次命令耗时。
     *
     * <p><b>集群兼容</b>：{@code RedisTemplate#scan} 只绑定初始连接所在的节点，
     * 集群模式下会漏掉其余 master 上的键；因此检测到集群连接时遍历全部 master
     * 节点逐个 SCAN（删除仍按 key hash 自动路由到所属节点）。
     *
     * <p><b>连接获取</b>：必须经 {@code connectionFactory.getConnection()} 取原始连接——
     * {@code redisTemplate.execute(RedisCallback)} 会把连接包装成 {@code StringRedisConnection}
     * 装饰代理，其 {@code instanceof RedisClusterConnection} 恒为 false，集群分支永远不会命中
     * （该缺陷由集群集成测试 RedisClusterRateLimiterIntegrationTest 发现）。
     * 用完必须 {@code close()}（原生连接不带模板的自动关闭语义）。
     */
    private void scanAndDeleteAll() {
        RedisConnection connection = redisTemplate.getConnectionFactory().getConnection();
        try {
            ScanOptions options = ScanOptions.scanOptions()
                    .match(KEY_PREFIX + "*").count(SCAN_BATCH_SIZE).build();
            if (connection instanceof RedisClusterConnection clusterConnection) {
                for (RedisClusterNode node : clusterConnection.clusterGetNodes()) {
                    if (node.isMaster()) {
                        scanAndDeleteBatch(clusterConnection.scan(node, options),
                                keys -> clusterConnection.del(keys.toArray(new byte[0][])));
                    }
                }
            } else {
                scanAndDeleteBatch(connection.keyCommands().scan(options),
                        keys -> connection.keyCommands().del(keys.toArray(new byte[0][])));
            }
        } finally {
            connection.close();
        }
    }

    /**
     * 消费一个 SCAN 游标并分批删除，批大小 {@value #SCAN_BATCH_SIZE}。
     */
    private void scanAndDeleteBatch(Cursor<byte[]> cursor, Consumer<List<byte[]>> deleter) {
        List<byte[]> batch = new ArrayList<>(SCAN_BATCH_SIZE);
        try (cursor) {
            while (cursor.hasNext()) {
                batch.add(cursor.next());
                if (batch.size() >= SCAN_BATCH_SIZE) {
                    deleter.accept(batch);
                    batch.clear();
                }
            }
        }
        if (!batch.isEmpty()) {
            deleter.accept(batch);
        }
    }
}
