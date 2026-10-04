package io.github.biglv666.apigovernance.alert.dedup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 集群告警去重闸门（0.7.0 新增）—— {@code SET NX EX} 抢占的原始实现。
 *
 * <h3>Redis 数据结构</h3>
 * <pre>
 * governance:alert:dedup:{dedupKey}   String "1"，TTL = 抢占窗口（秒）
 * </pre>
 *
 * <h3>故障语义（fail-open）</h3>
 * <p>Redis 异常时返回 {@code true}（视为抢到分发权），回退为各实例独立分发 ——
 * 与未开启集群去重的行为一致：告警永远不因去重组件故障而丢失，最坏情况退化为
 * 0.6.0 的每实例一条。故障 warn 限频每分钟一条。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
public class RedisAlertDeduplicationGate implements AlertDeduplicationGate {

    private static final Logger log = LoggerFactory.getLogger(RedisAlertDeduplicationGate.class);

    /** 去重键前缀（沿用 governance: 命名空间，与限流 ratelimit: 区分）。 */
    static final String KEY_PREFIX = "governance:alert:dedup:";

    /** fail-open 告警限频（毫秒）。 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final StringRedisTemplate redisTemplate;

    /** 上次 fail-open warn 时间戳（0 = 未告警过）。 */
    private final AtomicLong lastWarnMs = new AtomicLong();

    /**
     * 构造 Redis 去重闸门。
     *
     * @param redisTemplate Redis 模板
     */
    public RedisAlertDeduplicationGate(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    @Override
    public boolean tryAcquire(String dedupKey, long ttlMs) {
        try {
            Boolean acquired = redisTemplate.opsForValue()
                    .setIfAbsent(KEY_PREFIX + dedupKey, "1",
                            Duration.ofMillis(Math.max(1000, ttlMs)));
            return acquired == null || acquired;
        } catch (Exception e) {
            warnFailOpen(e);
            return true;
        }
    }

    @Override
    public String getName() {
        return "redis";
    }

    /**
     * fail-open 限频告警：Redis 故障期间每分钟至多一条 warn。
     */
    private void warnFailOpen(Exception e) {
        long now = System.currentTimeMillis();
        long last = lastWarnMs.get();
        if (now - last >= WARN_INTERVAL_MS && lastWarnMs.compareAndSet(last, now)) {
            log.warn("集群告警去重闸门暂不可用，回退单机分发（fail-open）- {}",
                    e.getMessage());
        }
    }
}
