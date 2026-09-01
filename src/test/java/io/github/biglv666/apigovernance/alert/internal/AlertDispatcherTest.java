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
}
