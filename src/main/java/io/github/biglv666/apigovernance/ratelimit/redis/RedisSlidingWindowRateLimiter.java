package io.github.biglv666.apigovernance.ratelimit.redis;

import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.RedisClusterConnection;
import org.springframework.data.redis.connection.RedisClusterNode;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Redis 滑动窗口限流器（分布式）。
 *
 * <p>「Redis 只封装」：通过 Sorted Set + Lua 脚本原子化地封装 Redis，实现精确的分布式滑动窗口。
 *
 * <h3>Redis 数据结构</h3>
 * <pre>
 * key: ratelimit:window:{apiKey}
 * type: Sorted Set
 * score: 请求时间戳（毫秒）
 * member: 请求唯一 ID（UUID，全局唯一，避免同毫秒同线程请求被 zadd 覆盖）
 * </pre>
 *
 * <h3>Lua 脚本逻辑</h3>
 * <ol>
 *   <li>计算窗口起点并删除窗口外旧记录（zremrangebyscore）；</li>
 *   <li>统计窗口内请求数（zcard）；</li>
 *   <li>未超限则写入当前请求并返回 1，否则返回 0；</li>
 *   <li>设置过期时间自动清理。</li>
 * </ol>
 *
 * @author API Governance Team
 * @since 1.0
 */
public class RedisSlidingWindowRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisSlidingWindowRateLimiter.class);

    /** Redis key 前缀。 */
    private static final String KEY_PREFIX = "ratelimit:window:";

    /** SCAN 每批扫描/删除的 key 数量。 */
    private static final int SCAN_BATCH_SIZE = 500;

    /**
     * 滑动窗口 Lua 脚本。
     * <p>KEYS[1]=key；ARGV[1]=阈值；ARGV[2]=窗口(秒)；ARGV[3]=请求唯一ID。
     * <p>当前时间取自 Redis 服务器（{@code TIME} 命令，秒+微秒）：多实例部署时应用节点
     * 时钟漂移不再影响窗口精度。{@code TIME} 属非确定性命令，脚本内先显式
     * {@code redis.replicate_commands()} 切换为效果复制，兼容 Redis 3.2–4.x
     * （逐字复制模式下「非确定性命令后写命令」会被拒绝）；Redis 5+ 默认即效果复制，
     * 该调用为幂等无害。
     * <p>返回 1 表示放行，0 表示拒绝。
     */
    private static final String LUA_SCRIPT =
            "local key = KEYS[1]\n" +
            "local limit = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2])\n" +
            "local requestId = ARGV[3]\n" +
            "\n" +
            "if redis.replicate_commands then redis.replicate_commands() end\n" +
            "local t = redis.call('TIME')\n" +
            "local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)\n" +
            "\n" +
            "local windowStart = now - (window * 1000)\n" +
            "redis.call('zremrangebyscore', key, 0, windowStart)\n" +
            "local count = redis.call('zcard', key)\n" +
            "\n" +
            "if count < limit then\n" +
            "  redis.call('zadd', key, now, requestId)\n" +
            "  redis.call('expire', key, window + 1)\n" +
            "  return 1\n" +
            "else\n" +
            "  return 0\n" +
            "end";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<Long> rateLimitScript;

    /**
     * 构造 Redis 滑动窗口限流器。
     *
     * @param redisTemplate Redis 模板（由 Spring 自动装配）
     */
    public RedisSlidingWindowRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.rateLimitScript = RedisScript.of(LUA_SCRIPT, Long.class);
        log.info("初始化 Redis 滑动窗口限流器");
    }

    @Override
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        // 异常不在此处吞掉：由自动配置包装的 FailSafeRateLimiter 统一按
        // fail-strategy（open=放行 / close=拒绝）处理降级与告警
        List<String> keys = Collections.singletonList(KEY_PREFIX + key);
        // member 必须全局唯一：UUID 而非「毫秒-线程ID」——后者在同毫秒同线程重复请求时
        // 会被 zadd 覆盖，导致窗口内计数偏少、限流偏松；窗口时间由 Lua 内的 Redis TIME 提供
        String requestId = UUID.randomUUID().toString();
        Long result = redisTemplate.execute(
                rateLimitScript,
                keys,
                String.valueOf(limit),
                String.valueOf(windowSeconds),
                requestId
        );
        return result != null && result == 1L;
    }

    @Override
    public String getName() {
        return "sliding-window-redis";
    }

    @Override
    public long getCurrentCount(String key) {
        try {
            Long count = redisTemplate.opsForZSet().zCard(KEY_PREFIX + key);
            return count != null ? count : 0;
        } catch (Exception e) {
            return -1;
        }
    }

    @Override
    public void reset(String key) {
        redisTemplate.delete(KEY_PREFIX + key);
        log.debug("重置 Redis 滑动窗口 - key: {}", key);
    }

    @Override
    public void resetAll() {
        // 使用 SCAN 游标分批匹配，避免 KEYS 在 key 规模大时阻塞 Redis
        scanAndDeleteAll();
        log.warn("清空所有 Redis 滑动窗口");
    }

    /**
     * SCAN 游标遍历并删除本限流器前缀下的全部 key。
     * 每批 {@value #SCAN_BATCH_SIZE} 条，删除也分批提交，控制单次命令耗时。
     *
     * <p><b>集群兼容</b>：{@code RedisTemplate#scan} 只绑定初始连接所在的节点，
     * 集群模式下会漏掉其余 master 上的键；因此检测到集群连接时遍历全部 master
     * 节点逐个 SCAN（删除仍按 key hash 自动路由到所属节点）。
     */
    private void scanAndDeleteAll() {
        redisTemplate.execute((RedisCallback<Void>) connection -> {
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
            return null;
        });
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
