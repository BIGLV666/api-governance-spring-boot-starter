package io.github.biglv666.apigovernance.alert.internal;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import io.github.biglv666.apigovernance.alert.dedup.AlertDeduplicationGate;
import io.github.biglv666.apigovernance.metrics.MetricsEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 告警分发器 —— 治理事件到告警通知器之间的统一闸门。
 *
 * <p>实现了 {@link MetricsEventListener}：从指标注册表接收「请求完成 / 请求被拒绝」事件，
 * 转换为 {@link GovernanceAlertEvent} 并分发给所有 {@link GovernanceAlertNotifier}。
 * 限流器故障事件（不走指标注册表）由 {@code FailSafeRateLimiter} 通过
 * {@link #publishRateLimiterFailure(String, String)} 直接触发。
 *
 * <h3>告警风暴抑制</h3>
 * <p><b>固定窗口</b>语义：同一 {@code (告警类型, apiKey)} 在 {@code suppressIntervalMs}
 * 窗口内只分发一次（首个事件生效，窗口内后续事件被静默丢弃且不续期）；窗口过后若
 * 问题仍存在（新事件到达），会再次分发。持续故障因此按窗口周期性重新告警，
 * 而不是只在首次告警后彻底静默。抑制状态保存在内存中，随进程重启清零。
 *
 * <h3>告警恢复通知（0.6.0 新增）</h3>
 * <p>两类恢复信号，均绕过抑制直接分发（恢复事件本身不再抑制）：
 * <ul>
 *   <li><b>精确信号</b>：{@link #markRecovered}——限流器故障恢复（故障后首次成功）
 *       由 {@code FailSafeRateLimiter} 主动上报；</li>
 *   <li><b>惰性扫描</b>：分发任一事件时扫描抑制表，某条目「曾抑制过事件且已安静
 *       超过一个抑制窗口」则认为状况解除，补发恢复通知（携带被抑制的条数）。
 *       依赖后续有其他事件触发扫描，系统完全安静时恢复通知会延迟。</li>
 * </ul>
 *
 * <h3>稳定性契约</h3>
 * <ul>
 *   <li>分发在请求线程上同步执行，但只做「判断 + 提交」，通知器内部不应有阻塞逻辑；</li>
 *   <li>任何通知器抛出的异常都会被捕获吞掉（warn 日志），绝不影响业务请求；</li>
 *   <li>未注册任何通知器时分发器自动空转（零开销）。</li>
 * </ul>
 *
 * @author API Governance Team
 * @since 0.2.0
 */
public class AlertDispatcher implements MetricsEventListener {

    private static final Logger log = LoggerFactory.getLogger(AlertDispatcher.class);

    /** 已注册的通知器（不可变列表）。 */
    private final List<GovernanceAlertNotifier> notifiers;

    /** 告警抑制窗口（毫秒）。 */
    private final long suppressIntervalMs;

    /** 慢方法阈值（毫秒），随慢方法告警事件携带。 */
    private final long slowThresholdMs;

    /** 是否启用恢复通知（0.6.0 新增，{@code api.governance.alert.recovery-enabled}）。 */
    private final boolean recoveryEnabled;

    /**
     * 集群去重闸门（0.7.0 新增，可为 null：未开启集群去重）。非 null 时本机抑制
     * 通过后再抢占集群分发权，窗口内全集群只发一条；故障由闸门实现 fail-open。
     */
    private final AlertDeduplicationGate clusterDedupGate;

    /** 集群去重默认窗口（毫秒）：本机抑制关闭（suppressIntervalMs=0）时的兜底 TTL。 */
    private static final long DEFAULT_DEDUP_TTL_MS = 10_000L;

    /** 恢复通知的集群去重窗口（毫秒）：恢复低频且必须尽快送达，用短窗口。 */
    private static final long RECOVERY_DEDUP_TTL_MS = 30_000L;

    /** 抑制状态：key = type|apiKey，value = 分发时间与抑制计数的可变条目。 */
    private final Map<String, SuppressionState> lastDispatchTime = new ConcurrentHashMap<>();

    /** 抑制状态最大条目数：超过后清理已出抑制窗口的条目，防止高基数 apiKey 下的缓慢泄漏。 */
    private static final int MAX_SUPPRESSION_ENTRIES = 10_000;

    /**
     * 单个 {@code (类型, apiKey)} 的抑制状态。
     */
    private static final class SuppressionState {

        /** 最近一次实际分发（放行告警）的时间戳。 */
        volatile long lastDispatchMs;

        /** 最近一次收到事件的时间戳（含被抑制的事件，用于恢复判断）。 */
        volatile long lastEventMs;

        /** 被抑制窗口静默丢弃的事件条数（用于恢复通知）。 */
        final AtomicLong suppressedCount = new AtomicLong();
    }

    /**
     * 构造告警分发器（恢复通知默认启用）。
     *
     * @param notifiers          通知器列表（可为空列表，此时分发器空转）
     * @param suppressIntervalMs 告警抑制窗口（毫秒），0 表示不抑制
     * @param slowThresholdMs    慢方法阈值（毫秒），随慢方法告警事件携带
     */
    public AlertDispatcher(List<GovernanceAlertNotifier> notifiers, long suppressIntervalMs,
                           long slowThresholdMs) {
        this(notifiers, suppressIntervalMs, slowThresholdMs, true);
    }

    /**
     * 构造告警分发器。
     *
     * @param notifiers          通知器列表（可为空列表，此时分发器空转）
     * @param suppressIntervalMs 告警抑制窗口（毫秒），0 表示不抑制
     * @param slowThresholdMs    慢方法阈值（毫秒），随慢方法告警事件携带
     * @param recoveryEnabled    是否启用恢复通知（0.6.0 新增）
     */
    public AlertDispatcher(List<GovernanceAlertNotifier> notifiers, long suppressIntervalMs,
                           long slowThresholdMs, boolean recoveryEnabled) {
        this(notifiers, suppressIntervalMs, slowThresholdMs, recoveryEnabled, null);
    }

    /**
     * 构造告警分发器（0.7.0 新增，支持集群去重）。
     *
     * @param notifiers          通知器列表（可为空列表，此时分发器空转）
     * @param suppressIntervalMs 告警抑制窗口（毫秒），0 表示不抑制
     * @param slowThresholdMs    慢方法阈值（毫秒），随慢方法告警事件携带
     * @param recoveryEnabled    是否启用恢复通知（0.6.0 新增）
     * @param clusterDedupGate   集群去重闸门（0.7.0 新增，null 表示不开启集群去重）
     */
    public AlertDispatcher(List<GovernanceAlertNotifier> notifiers, long suppressIntervalMs,
                           long slowThresholdMs, boolean recoveryEnabled,
                           AlertDeduplicationGate clusterDedupGate) {
        this.notifiers = List.copyOf(notifiers);
        this.suppressIntervalMs = Math.max(0, suppressIntervalMs);
        this.slowThresholdMs = slowThresholdMs;
        this.recoveryEnabled = recoveryEnabled;
        this.clusterDedupGate = clusterDedupGate;
    }

    /**
     * 请求完成事件：慢方法时触发 {@code SLOW_METHOD} 告警。
     */
    @Override
    public void onResult(String apiKey, long elapsedMs, boolean success, boolean slow,
                         String httpMethod, String path, String error) {
        if (!slow) {
            return;
        }
        dispatch(GovernanceAlertEvent.slowMethod(apiKey, httpMethod, path, elapsedMs, slowThresholdMs));
    }

    /**
     * 请求被拒绝事件：触发 {@code RATE_LIMIT_REJECT} 告警。
     */
    @Override
    public void onReject(String apiKey, String httpMethod, String path, String reason) {
        dispatch(GovernanceAlertEvent.rateLimitReject(apiKey, httpMethod, path, reason));
    }

    /**
     * 限流器故障告警（由 {@code FailSafeRateLimiter} 在捕获到限流器异常时调用）。
     *
     * @param rateLimiterName 限流器名称
     * @param error           故障摘要（仅异常 message，不含堆栈）
     */
    public void publishRateLimiterFailure(String rateLimiterName, String error) {
        dispatch(GovernanceAlertEvent.rateLimiterFailure(rateLimiterName, error));
    }

    /**
     * 异步任务被拒绝告警（0.5.0 新增，由默认拒绝任务处理器在队列拒绝时调用）。
     *
     * @param action  异步动作名
     * @param handler Handler 方法标识
     * @param error   拒绝原因摘要
     */
    public void publishAsyncTaskRejected(String action, String handler, String error) {
        dispatch(GovernanceAlertEvent.asyncTaskRejected(action, handler, error));
    }

    /**
     * 分发一条告警：先惰性扫描恢复，再做抑制判断，通过后抢占集群分发权（0.7.0 新增），
     * 最后逐个通知并隔离异常。
     */
    private void dispatch(GovernanceAlertEvent event) {
        if (notifiers.isEmpty()) {
            return;
        }
        if (recoveryEnabled) {
            sweepForRecoveries(System.currentTimeMillis());
        }
        if (isSuppressed(event)) {
            return;
        }
        if (clusterDedupGate != null && !acquireClusterDispatchRight(event)) {
            return;
        }
        for (GovernanceAlertNotifier notifier : notifiers) {
            try {
                notifier.notify(event);
            } catch (Exception e) {
                log.warn("告警通知器执行异常 - notifier: {}, event: {}, 错误: {}",
                        notifier.getName(), event.getType(), e.getMessage());
            }
        }
    }

    /**
     * 抢占集群分发权：抢到返回 true 并继续分发；未抢到（窗口内其他实例已分发）
     * 把事件计入本地抑制计数（供恢复语义沿用）后静默丢弃。
     *
     * <p>闸门 TTL = 本机抑制窗口（关闭时取默认 10 秒兜底），保证集群去重窗口
     * 与单机抑制窗口对齐。
     */
    private boolean acquireClusterDispatchRight(GovernanceAlertEvent event) {
        String dedupKey = event.getType() + "|" + event.getApiKey();
        long ttl = suppressIntervalMs > 0 ? suppressIntervalMs : DEFAULT_DEDUP_TTL_MS;
        if (clusterDedupGate.tryAcquire(dedupKey, ttl)) {
            return true;
        }
        SuppressionState state = lastDispatchTime.get(dedupKey);
        if (state != null) {
            state.suppressedCount.incrementAndGet();
        }
        return false;
    }

    /**
     * 惰性恢复扫描：某条目「曾抑制过事件且已安静超过一个抑制窗口」则认为状况解除，
     * 补发恢复通知并移除条目。由下一次任意事件分发触发（零后台线程），
     * 系统完全安静时恢复通知会延迟到下一个事件到达。
     */
    private void sweepForRecoveries(long now) {
        if (suppressIntervalMs <= 0 || lastDispatchTime.isEmpty()) {
            return;
        }
        for (Map.Entry<String, SuppressionState> entry : lastDispatchTime.entrySet()) {
            SuppressionState state = entry.getValue();
            long suppressed = state.suppressedCount.get();
            if (suppressed > 0 && (now - state.lastEventMs) >= suppressIntervalMs
                    && lastDispatchTime.remove(entry.getKey(), state)) {
                String[] parts = entry.getKey().split("\\|", 2);
                GovernanceAlertEvent.Type originalType = parseType(parts[0]);
                String apiKey = parts.length > 1 ? parts[1] : null;
                dispatchRecovery(GovernanceAlertEvent.recovery(originalType, apiKey, suppressed));
            }
        }
    }

    /**
     * 显式恢复信号（0.6.0 新增）：由拥有精确「状况解除」知识的组件调用——
     * 例如 {@code FailSafeRateLimiter} 在故障后首次成功时上报限流器恢复。
     * 抑制表存在该条目即发恢复通知（绕过抑制），不存在则视为无活动告警、跳过。
     *
     * @param type   原告警类型
     * @param apiKey 告警主体（与告警事件的 {@code apiKey} 对应）
     */
    public void markRecovered(GovernanceAlertEvent.Type type, String apiKey) {
        if (!recoveryEnabled || notifiers.isEmpty()) {
            return;
        }
        String key = type + "|" + apiKey;
        SuppressionState state = lastDispatchTime.remove(key);
        if (state == null) {
            return;
        }
        dispatchRecovery(GovernanceAlertEvent.recovery(type, apiKey, state.suppressedCount.get()));
    }

    /**
     * 分发恢复事件：绕过抑制（恢复事件低频且必须送达），异常隔离与普通分发一致。
     * 开启集群去重时恢复事件同样抢占集群分发权（独立短窗口 30s，与告警窗口解耦），
     * 保证全集群只补发一条恢复；未抢到的实例已从抑制表移除条目，不会重复触发。
     */
    private void dispatchRecovery(GovernanceAlertEvent event) {
        if (clusterDedupGate != null
                && !clusterDedupGate.tryAcquire(event.getType() + "|" + event.getApiKey() + "|recovery",
                        RECOVERY_DEDUP_TTL_MS)) {
            log.debug("恢复通知已被其他集群实例分发，跳过 - type: {}, apiKey: {}",
                    event.getType(), event.getApiKey());
            return;
        }
        log.info("告警恢复 - type: {}, apiKey: {}, 抑制期内丢弃: {} 条",
                event.getType(), event.getApiKey(), event.getSuppressedCount());
        for (GovernanceAlertNotifier notifier : notifiers) {
            try {
                notifier.notify(event);
            } catch (Exception e) {
                log.warn("告警恢复通知器执行异常 - notifier: {}, 错误: {}",
                        notifier.getName(), e.getMessage());
            }
        }
    }

    /**
     * 从抑制表 key 前缀解析告警类型；解析失败（跨版本 key 残留等）回退 SLOW_METHOD，
     * 仅影响恢复事件的类型标注，不影响分发。
     */
    private GovernanceAlertEvent.Type parseType(String name) {
        try {
            return GovernanceAlertEvent.Type.valueOf(name);
        } catch (IllegalArgumentException e) {
            return GovernanceAlertEvent.Type.SLOW_METHOD;
        }
    }

    /**
     * 判断同一 {@code (类型, apiKey)} 是否处于抑制窗口内（固定窗口语义）。
     *
     * <p>先读后写：窗口内的事件只被静默丢弃并累计抑制计数、刷新最后事件时间，
     * <b>绝不刷新</b>已记录的分发时间戳——若在判断前先续期，持续故障的窗口会被
     * 无限重置，导致只告警一次后彻底静默。仅当放行（首报或窗口已过）时才记录新分发时间。
     *
     * <p>抑制表有界：条目数超过 {@value #MAX_SUPPRESSION_ENTRIES} 时清理已出抑制窗口的条目，
     * 防止 SpEL 参数维度限流等高基数 apiKey 场景下抑制表无限增长。
     */
    private boolean isSuppressed(GovernanceAlertEvent event) {
        if (suppressIntervalMs <= 0) {
            return false;
        }
        String key = event.getType() + "|" + event.getApiKey();
        long now = System.currentTimeMillis();
        // 新建条目 lastDispatchMs=0：now - 0 远大于窗口 → 首报必然放行
        SuppressionState state = lastDispatchTime.computeIfAbsent(key, k -> new SuppressionState());
        if (lastDispatchTime.size() > MAX_SUPPRESSION_ENTRIES) {
            evictExpiredSuppressions(now);
        }
        if (now - state.lastDispatchMs < suppressIntervalMs) {
            // 抑制窗口内：计数并刷新最后事件时间，绝不续期分发时间戳
            state.lastEventMs = now;
            state.suppressedCount.incrementAndGet();
            return true;
        }
        // 窗口已过的新一轮首报：重置分发时间并清零抑制计数
        state.lastDispatchMs = now;
        state.lastEventMs = now;
        state.suppressedCount.set(0);
        return false;
    }

    /**
     * 清理已出抑制窗口的条目。使用 {@code remove(key, value)} 两段式删除，
     * 避免并发下误删同 key 更新的时间戳。
     *
     * <p>{@code ConcurrentHashMap} 弱一致迭代在删除过程中可能跳过部分条目，
     * 因此循环清理直到一轮无删除或已回落到上限以内。
     */
    private void evictExpiredSuppressions(long now) {
        boolean removedAny;
        do {
            removedAny = false;
            for (Map.Entry<String, SuppressionState> entry : lastDispatchTime.entrySet()) {
                SuppressionState state = entry.getValue();
                if ((now - state.lastEventMs) >= suppressIntervalMs
                        && lastDispatchTime.remove(entry.getKey(), state)) {
                    removedAny = true;
                }
            }
        } while (removedAny && lastDispatchTime.size() > MAX_SUPPRESSION_ENTRIES);
    }

    /**
     * 抑制表当前条目数（供测试与运维观测）。
     */
    int getSuppressionEntryCount() {
        return lastDispatchTime.size();
    }

    /**
     * 通知器数量（供管理接口/日志展示）。
     */
    public int getNotifierCount() {
        return notifiers.size();
    }
}
