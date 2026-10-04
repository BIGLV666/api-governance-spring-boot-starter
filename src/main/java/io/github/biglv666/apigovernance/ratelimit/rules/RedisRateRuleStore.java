package io.github.biglv666.apigovernance.ratelimit.rules;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 版动态规则存储（0.7.0 新增）—— 多实例共享同一份规则，全集群一致。
 *
 * <h3>Redis 数据结构</h3>
 * <pre>
 * ratelimit:rules           Hash   field=pattern, value="limit|window|updatedAtMs"
 * ratelimit:rules:version   String 版本号，每次写入 INCR
 * </pre>
 *
 * <h3>读写路径</h3>
 * <ul>
 *   <li><b>写</b>（管理接口，低频）：HSET/HDEL 规则 + INCR 版本号，随后本节点立即重载；
 *       写失败抛 {@link IllegalStateException} 交由管理接口反馈，本地快照不受影响；</li>
 *   <li><b>读</b>（请求热路径）：只读进程内 volatile 快照，无任何远程调用；</li>
 *   <li><b>同步</b>：后台 daemon 单线程按 {@code refresh-interval-ms} 比对版本号
 *       （一次 GET），变化才拉全量规则重建快照 —— 集群传播延迟上界即刷新间隔。</li>
 * </ul>
 *
 * <h3>故障语义（fail-stale）</h3>
 * <p>Redis 读故障时保留最后一次成功快照继续生效（规则是降级兜底手段，本身不该
 * 因存储故障失效），并按每分钟至多一条的频率记 warn；Redis 恢复后自动追上最新版本。
 * 故障绝不向上抛出，不影响业务请求。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
public class RedisRateRuleStore implements RateRuleStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisRateRuleStore.class);

    /** 规则 Hash key（沿用限流器 ratelimit: 前缀命名空间）。 */
    static final String RULES_KEY = "ratelimit:rules";

    /** 规则版本号 key，每次写入 INCR。 */
    static final String VERSION_KEY = "ratelimit:rules:version";

    /** fail-stale 告警限频（毫秒），避免 Redis 长时间故障时的日志风暴。 */
    private static final long WARN_INTERVAL_MS = 60_000L;

    private final StringRedisTemplate redisTemplate;

    /** 规则数上限（按 Redis 侧规则条数校验）。 */
    private final int maxRules;

    /** 版本号轮询间隔（毫秒）。 */
    private final long refreshIntervalMs;

    /** 当前快照：volatile 发布，热路径读无锁。 */
    private volatile RuleMatcher matcher = RuleMatcher.of(Map.of());

    /** 上次已知版本号；-1 表示尚未成功读取过（首次成功 GET 必然触发全量加载）。 */
    private volatile long knownVersion = -1L;

    /** 上次 fail-stale warn 时间戳（0 = 未告警过）。 */
    private final AtomicLong lastStaleWarnMs = new AtomicLong();

    /** 版本轮询调度器（单线程 daemon）。 */
    private final ScheduledExecutorService scheduler;

    /**
     * 构造 Redis 规则存储并立即开始同步。
     *
     * @param redisTemplate      Redis 模板
     * @param refreshIntervalMs  版本号轮询间隔（毫秒，至少 500）
     * @param maxRules           规则数上限（至少 1）
     */
    public RedisRateRuleStore(StringRedisTemplate redisTemplate, long refreshIntervalMs,
                              int maxRules) {
        this.redisTemplate = redisTemplate;
        this.refreshIntervalMs = Math.max(500, refreshIntervalMs);
        this.maxRules = Math.max(1, maxRules);
        // 初始加载失败不阻止启动：快照保持空，轮询线程会在 Redis 恢复后补上
        refreshIfVersionChanged();
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "api-governance-rule-refresh");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::safeRefresh,
                this.refreshIntervalMs, this.refreshIntervalMs, TimeUnit.MILLISECONDS);
        log.info("初始化 Redis 动态规则存储 - 刷新间隔: {}ms, 当前规则数: {}",
                this.refreshIntervalMs, matcher.size());
    }

    @Override
    public Optional<DynamicRateRule> findMatch(String apiKey) {
        return matcher.findMatch(apiKey);
    }

    @Override
    public Map<String, DynamicRateRule> all() {
        return matcher.rules();
    }

    @Override
    public void put(String pattern, int limit, int window) {
        DynamicRateRule rule = DynamicRateRule.of(pattern, limit, window);
        try {
            boolean replacing = matcher.rules().containsKey(rule.getPattern());
            if (!replacing) {
                Long remoteSize = redisTemplate.opsForHash().size(RULES_KEY);
                long size = remoteSize != null ? remoteSize : matcher.size();
                if (size >= maxRules) {
                    throw new IllegalArgumentException(
                            "动态规则数已达上限 " + maxRules + "，请先删除无用规则或调大 "
                                    + "api.governance.rate-limit.dynamic-rules.max-rules");
                }
            }
            redisTemplate.opsForHash().put(RULES_KEY, rule.getPattern(),
                    encode(rule));
            redisTemplate.opsForValue().increment(VERSION_KEY);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("写入 Redis 动态规则失败: " + e.getMessage(), e);
        }
        // 本节点立即重载，避免等待下一个轮询周期；重载失败保留旧快照（轮询会追上）
        refreshIfVersionChanged();
    }

    @Override
    public void remove(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return;
        }
        String trimmed = pattern.trim();
        try {
            redisTemplate.opsForHash().delete(RULES_KEY, trimmed);
            redisTemplate.opsForValue().increment(VERSION_KEY);
        } catch (Exception e) {
            throw new IllegalStateException("删除 Redis 动态规则失败: " + e.getMessage(), e);
        }
        refreshIfVersionChanged();
    }

    @Override
    public String getName() {
        return "redis";
    }

    /**
     * 停止版本轮询（容器销毁回调）。
     */
    @Override
    public void close() {
        scheduler.shutdownNow();
    }

    /**
     * 立即触发一次版本比对刷新（同包测试用，绕过轮询间隔，不吞异常）。
     */
    void refreshForTest() {
        refreshIfVersionChanged();
    }

    /**
     * 轮询入口：吞掉一切异常 —— 调度任务抛异常会被 ScheduledExecutor 取消，
     * 之后版本将永远不再刷新。
     */
    private void safeRefresh() {
        try {
            refreshIfVersionChanged();
        } catch (Throwable t) {
            warnStale("动态规则版本轮询异常: " + t.getMessage());
        }
    }

    /**
     * 比对 Redis 侧版本号，变化（或首次）时拉取全量规则重建快照。
     * 任何读失败按 fail-stale 处理：保留旧快照 + 限频 warn。
     */
    private void refreshIfVersionChanged() {
        long currentVersion;
        try {
            // 版本 key 不存在（从未写入过规则）是正常场景，按版本 0 处理而非故障
            String versionRaw = redisTemplate.opsForValue().get(VERSION_KEY);
            currentVersion = versionRaw != null ? Long.parseLong(versionRaw) : 0L;
        } catch (Exception e) {
            warnStale("读取规则版本号失败: " + e.getMessage());
            return;
        }
        if (currentVersion == knownVersion) {
            return;
        }
        Map<Object, Object> raw;
        try {
            raw = redisTemplate.opsForHash().entries(RULES_KEY);
        } catch (Exception e) {
            warnStale("读取动态规则失败: " + e.getMessage());
            return;
        }
        Map<String, DynamicRateRule> next = new HashMap<>(raw.size());
        for (Map.Entry<Object, Object> entry : raw.entrySet()) {
            String storedPattern = String.valueOf(entry.getKey());
            DynamicRateRule rule = decode(storedPattern, entry.getValue());
            if (rule != null) {
                next.put(storedPattern, rule);
            }
        }
        matcher = RuleMatcher.of(next);
        knownVersion = currentVersion;
        if (log.isDebugEnabled()) {
            log.debug("动态规则快照已刷新 - 版本: {}, 规则数: {}", currentVersion, next.size());
        }
    }

    /**
     * fail-stale 限频告警：Redis 故障期间每分钟至多一条 warn。
     */
    private void warnStale(String message) {
        long now = System.currentTimeMillis();
        long last = lastStaleWarnMs.get();
        if (now - last >= WARN_INTERVAL_MS && lastStaleWarnMs.compareAndSet(last, now)) {
            log.warn("动态规则存储暂不可用，沿用最后一次快照（{} 条规则）- {}",
                    matcher.size(), message);
        }
    }

    /**
     * 编码规则为 Hash value：{@code limit|window|updatedAtMs}（纯数字字段，无转义需求）。
     */
    private static String encode(DynamicRateRule rule) {
        return rule.getLimit() + "|" + rule.getWindow() + "|" + rule.getUpdatedAtMs();
    }

    /**
     * 解码 Hash value；格式损坏（跨版本残留等）跳过该条并 warn，不影响其余规则。
     */
    private DynamicRateRule decode(String pattern, Object value) {
        try {
            String[] parts = String.valueOf(value).split("\\|");
            return DynamicRateRule.restore(pattern, Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]), Long.parseLong(parts[2]));
        } catch (Exception e) {
            log.warn("跳过无法解析的动态规则 - pattern: '{}', value: '{}'", pattern, value);
            return null;
        }
    }
}
