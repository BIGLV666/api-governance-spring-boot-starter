package io.github.biglv666.apigovernance.alert.internal;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import io.github.biglv666.apigovernance.metrics.MetricsEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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

    /** 抑制状态：key = type|apiKey，value = 上次分发时间戳。 */
    private final Map<String, Long> lastDispatchTime = new ConcurrentHashMap<>();

    /** 抑制状态最大条目数：超过后清理已出抑制窗口的条目，防止高基数 apiKey 下的缓慢泄漏。 */
    private static final int MAX_SUPPRESSION_ENTRIES = 10_000;

    /**
     * 构造告警分发器。
     *
     * @param notifiers          通知器列表（可为空列表，此时分发器空转）
     * @param suppressIntervalMs 告警抑制窗口（毫秒），0 表示不抑制
     * @param slowThresholdMs    慢方法阈值（毫秒），随慢方法告警事件携带
     */
    public AlertDispatcher(List<GovernanceAlertNotifier> notifiers, long suppressIntervalMs,
                           long slowThresholdMs) {
        this.notifiers = List.copyOf(notifiers);
        this.suppressIntervalMs = Math.max(0, suppressIntervalMs);
        this.slowThresholdMs = slowThresholdMs;
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
     * 分发一条告警：先做抑制判断，再逐个通知并隔离异常。
     */
    private void dispatch(GovernanceAlertEvent event) {
        if (notifiers.isEmpty()) {
            return;
        }
        if (isSuppressed(event)) {
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
     * 判断同一 {@code (类型, apiKey)} 是否处于抑制窗口内（固定窗口语义）。
     *
     * <p>先读后写：窗口内的事件只被静默丢弃，<b>绝不刷新</b>已记录的分发时间戳——
     * 若在判断前先 {@code put(key, now)} 续期，持续故障（如 Redis 宕机）的窗口会被
     * 无限重置，导致只告警一次后彻底静默。仅当放行（首报或窗口已过）时才记录新时间戳。
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
        Long last = lastDispatchTime.get(key);
        if (last != null && (now - last) < suppressIntervalMs) {
            return true;
        }
        lastDispatchTime.put(key, now);
        if (lastDispatchTime.size() > MAX_SUPPRESSION_ENTRIES) {
            evictExpiredSuppressions(now);
        }
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
            for (Map.Entry<String, Long> entry : lastDispatchTime.entrySet()) {
                Long last = entry.getValue();
                if (last != null && (now - last) >= suppressIntervalMs
                        && lastDispatchTime.remove(entry.getKey(), last)) {
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
