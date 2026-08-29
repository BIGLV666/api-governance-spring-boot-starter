package io.github.biglv666.apigovernance.ratelimit.redis;

import io.github.biglv666.apigovernance.ratelimit.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.Collections;
import java.util.List;

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

    /**
     * 令牌桶 Lua 脚本。
     * <p>KEYS[1]=key；ARGV[1]=容量(limit)；ARGV[2]=窗口(秒)；ARGV[3]=当前时间戳(毫秒)。
     * <p>返回 1 表示放行，0 表示拒绝。
     */
    private static final String LUA_SCRIPT =
            "local key = KEYS[1]\n" +
            "local capacity = tonumber(ARGV[1])\n" +
            "local window = tonumber(ARGV[2])\n" +
            "local now = tonumber(ARGV[3])\n" +
            "local rate = capacity / window\n" +
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
        List<String> keys = Collections.singletonList(KEY_PREFIX + key);
        Long result = redisTemplate.execute(
                rateLimitScript,
                keys,
                String.valueOf(limit),
                String.valueOf(Math.max(1, windowSeconds)),
                String.valueOf(System.currentTimeMillis())
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
        // 注意：KEYS 仅适合管理操作（低频）；生产环境如 key 规模大可改用 SCAN 游标
        redisTemplate.keys(KEY_PREFIX + "*").forEach(redisTemplate::delete);
        log.warn("清空所有 Redis 令牌桶");
    }
}
