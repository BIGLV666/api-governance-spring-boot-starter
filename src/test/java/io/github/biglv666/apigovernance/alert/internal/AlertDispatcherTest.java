package io.github.biglv666.apigovernance.alert.internal;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 告警分发器单元测试：验证事件转换、告警风暴抑制与通知器异常隔离。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
class AlertDispatcherTest {

    /**
     * 收集事件的测试通知器。
     */
    static class CollectingNotifier implements GovernanceAlertNotifier {
        final List<GovernanceAlertEvent> events = new ArrayList<>();

        @Override
        public void notify(GovernanceAlertEvent event) {
            events.add(event);
        }
    }

    @Test
    void slowResultDispatchesSlowMethodAlert() {
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 0, 1000);

        dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
        dispatcher.onResult("api", 100, true, false, "GET", "/x", null);

        assertEquals(1, notifier.events.size());
        GovernanceAlertEvent event = notifier.events.get(0);
        assertEquals(GovernanceAlertEvent.Type.SLOW_METHOD, event.getType());
        assertEquals(1500, event.getElapsedMs());
        assertEquals(1000, event.getThresholdMs());
    }

    @Test
    void rejectDispatchesRateLimitAlert() {
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 0, 1000);

        dispatcher.onReject("api", "GET", "/x", "too many");

        assertEquals(1, notifier.events.size());
        assertEquals(GovernanceAlertEvent.Type.RATE_LIMIT_REJECT, notifier.events.get(0).getType());
    }

    @Test
    void suppressionSuppressesSameTypeAndKeyWithinWindow() throws Exception {
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 60_000, 1000);

        dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
        dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
        // 不同 apiKey 不受抑制影响
        dispatcher.onResult("api2", 1500, true, true, "GET", "/x", null);

        assertEquals(2, notifier.events.size());
    }

    @Test
    void notifierExceptionIsIsolated() {
        GovernanceAlertNotifier broken = event -> {
            throw new IllegalStateException("boom");
        };
        CollectingNotifier healthy = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(broken, healthy), 0, 1000);

        dispatcher.onReject("api", "GET", "/x", "reason");

        // 通知器抛异常不影响其他通知器，也不向调用方传播
        assertEquals(1, healthy.events.size());
    }

    @Test
    void noNotifiersIsNoOp() {
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(), 0, 1000);
        dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
        dispatcher.onReject("api", "GET", "/x", "reason");
        dispatcher.publishRateLimiterFailure("redis", "down");
        assertEquals(0, dispatcher.getNotifierCount());
        assertTrue(true);
    }

    @Test
    void rateLimiterFailureAlertCarriesLimiterName() {
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 0, 1000);

        dispatcher.publishRateLimiterFailure("sliding-window-redis", "connection refused");

        assertEquals(1, notifier.events.size());
        GovernanceAlertEvent event = notifier.events.get(0);
        assertEquals(GovernanceAlertEvent.Type.RATE_LIMITER_FAILURE, event.getType());
        assertEquals("sliding-window-redis", event.getApiKey());
    }

    @Test
    void suppressionMapStaysBoundedUnderHighCardinalityKeys() throws Exception {
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 10, 1000);

        // 高基数 apiKey 持续触发慢方法告警（0.2.0 中抑制表会无限增长）
        for (int i = 0; i < 20_000; i++) {
            dispatcher.onResult("api-" + i, 1500, true, true, "GET", "/x", null);
        }
        // 等待抑制窗口全部滑出，再触发一次分发以启动过期清理
        Thread.sleep(30);
        dispatcher.onResult("api-final", 1500, true, true, "GET", "/x", null);

        // 契约是「有界」而非「清空」：抑制表始终不超过上限
        assertTrue(dispatcher.getSuppressionEntryCount() <= 10_000,
                "抑制表应保持有界，实际: " + dispatcher.getSuppressionEntryCount());
    }

    @Test
    void suppressionIsFixedWindowAndAlertsAgainAfterWindowPasses() {
        // 回归：抑制曾在每次事件时刷新时间戳（滑动续期），持续故障只告警一次后彻底静默；
        // 固定窗口语义下，事件持续流动跨过窗口边界时应再次分发，且不触发恢复
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 40, 1000);

        // 事件以 ~2ms 间隔持续流动 30 次（远超 40ms 窗口），保证无静默期
        long alerts = 0;
        for (int i = 0; i < 30; i++) {
            dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        alerts = notifier.events.stream().filter(e -> !e.isRecovered()).count();
        assertTrue(alerts >= 2,
                "事件持续流动跨过窗口边界应再次告警（实际告警: " + alerts + " 条）");
        assertTrue(notifier.events.stream().noneMatch(GovernanceAlertEvent::isRecovered),
                "无静默期就不应产生恢复事件");
    }

    @Test
    void recoveryEmittedWhenQuietForFullWindow() throws Exception {
        // 0.6.0 惰性恢复：曾抑制过事件的条目安静超过一个窗口后，
        // 由下一次任意事件分发触发补发恢复通知（携带被抑制条数）。
        // 注意恢复事件先于触发它的事件本身入列（扫描发生在分发开头）
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 40, 1000);

        // 首报（1 条告警）+ 窗口内 3 条被抑制
        dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
        for (int i = 0; i < 3; i++) {
            dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
            Thread.sleep(2);
        }
        assertEquals(1, notifier.events.size());
        assertTrue(notifier.events.stream().noneMatch(GovernanceAlertEvent::isRecovered));

        // 安静超过抑制窗口后，另一 apiKey 的事件触发扫描 → 补发恢复
        Thread.sleep(50);
        dispatcher.onResult("other-api", 1500, true, true, "GET", "/x", null);

        assertEquals(3, notifier.events.size());
        GovernanceAlertEvent recovery = notifier.events.get(1);
        assertTrue(recovery.isRecovered());
        assertEquals(GovernanceAlertEvent.Type.SLOW_METHOD, recovery.getType());
        assertEquals("api", recovery.getApiKey());
        assertEquals(3, recovery.getSuppressedCount());
    }

    @Test
    void markRecoveredEmitsImmediatelyAndBypassesSuppression() {
        // 0.6.0 精确恢复信号：限流器故障恢复由 FailSafeRateLimiter 显式上报，
        // 不等待惰性扫描、不受抑制窗口约束
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 60_000, 1000);

        dispatcher.publishRateLimiterFailure("redis", "connection refused");
        dispatcher.markRecovered(GovernanceAlertEvent.Type.RATE_LIMITER_FAILURE, "redis");

        assertEquals(2, notifier.events.size());
        GovernanceAlertEvent recovery = notifier.events.get(1);
        assertTrue(recovery.isRecovered());
        assertEquals(GovernanceAlertEvent.Type.RATE_LIMITER_FAILURE, recovery.getType());
        assertEquals(0, recovery.getSuppressedCount());
    }

    @Test
    void markRecoveredSkipsWhenNoActiveAlert() {
        // 没有活动告警时显式恢复信号应空转，不产生噪音事件
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 60_000, 1000);

        dispatcher.markRecovered(GovernanceAlertEvent.Type.RATE_LIMITER_FAILURE, "redis");

        assertEquals(0, notifier.events.size());
    }

    @Test
    void recoveryCanBeDisabled() throws Exception {
        CollectingNotifier notifier = new CollectingNotifier();
        AlertDispatcher dispatcher = new AlertDispatcher(List.of(notifier), 40, 1000, false);

        dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
        for (int i = 0; i < 3; i++) {
            dispatcher.onResult("api", 1500, true, true, "GET", "/x", null);
            Thread.sleep(2);
        }
        Thread.sleep(50);
        dispatcher.onResult("other-api", 1500, true, true, "GET", "/x", null);
        dispatcher.markRecovered(GovernanceAlertEvent.Type.RATE_LIMITER_FAILURE, "redis");

        // recovery-enabled=false：既无惰性恢复也无显式恢复
        assertTrue(notifier.events.stream().noneMatch(GovernanceAlertEvent::isRecovered));
    }
}
