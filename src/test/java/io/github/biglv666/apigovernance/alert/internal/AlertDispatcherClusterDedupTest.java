package io.github.biglv666.apigovernance.alert.internal;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import io.github.biglv666.apigovernance.alert.dedup.AlertDeduplicationGate;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 集群告警去重分发测试：同一告警窗口内全集群只发一条、恢复通知去重、
 * 闸门故障 fail-open 回退单机行为。
 *
 * <p>用两个共享同一伪闸门的分发器实例模拟双节点集群；闸门的 Redis 原生语义
 * （SET NX EX）在 {@code RedisAlertDeduplicationGateTest} 中单独验证。
 *
 * @author API Governance Team
 * @since 0.7.0
 */
class AlertDispatcherClusterDedupTest {

    /** 收集事件的测试通知器。 */
    static class CollectingNotifier implements GovernanceAlertNotifier {
        final List<GovernanceAlertEvent> events = new ArrayList<>();

        @Override
        public void notify(GovernanceAlertEvent event) {
            events.add(event);
        }
    }

    /**
     * 伪集群闸门：SETNX + TTL 语义的 JVM 实现，时钟可手动推进保证测试确定性。
     */
    static class FakeGate implements AlertDeduplicationGate {
        final Map<String, Long> heldUntil = new ConcurrentHashMap<>();
        long now = System.currentTimeMillis();
        boolean failOpen;

        @Override
        public boolean tryAcquire(String dedupKey, long ttlMs) {
            if (failOpen) {
                return true;
            }
            Long until = heldUntil.get(dedupKey);
            if (until != null && until > now) {
                return false;
            }
            heldUntil.put(dedupKey, now + ttlMs);
            return true;
        }

        @Override
        public String getName() {
            return "fake";
        }

        void advance(long ms) {
            now += ms;
        }
    }

    private static final long SUPPRESS_MS = 60;

    private CollectingNotifier notifierA;
    private CollectingNotifier notifierB;
    private AlertDispatcher nodeA;
    private AlertDispatcher nodeB;
    private FakeGate gate;

    private void setUpCluster() {
        notifierA = new CollectingNotifier();
        notifierB = new CollectingNotifier();
        gate = new FakeGate();
        nodeA = new AlertDispatcher(List.of(notifierA), SUPPRESS_MS, 1000, true, gate);
        nodeB = new AlertDispatcher(List.of(notifierB), SUPPRESS_MS, 1000, true, gate);
    }

    @Test
    void onlyFirstInstanceNotifiesWithinWindow() {
        setUpCluster();

        nodeA.onResult("api", 1500, true, true, "GET", "/x", null);
        nodeB.onResult("api", 1500, true, true, "GET", "/x", null);
        nodeB.onResult("api", 1500, true, true, "GET", "/x", null);

        assertEquals(1, notifierA.events.size());
        assertEquals(0, notifierB.events.size());
    }

    @Test
    void windowExpiryLetsNextInstanceNotify() {
        setUpCluster();

        nodeA.onResult("api", 1500, true, true, "GET", "/x", null);
        nodeB.onResult("api", 1500, true, true, "GET", "/x", null);
        assertEquals(1, notifierA.events.size());
        assertEquals(0, notifierB.events.size());

        // 窗口过期（推进伪时钟 + 抑制窗口静默期）后再次触发：本机抑制与集群闸门都放行
        gate.advance(SUPPRESS_MS + 10);
        try {
            Thread.sleep(SUPPRESS_MS + 10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        nodeA.onResult("api", 1500, true, true, "GET", "/x", null);
        assertEquals(2, notifierA.events.size());
    }

    @Test
    void recoveryIsDeduplicatedAcrossCluster() throws Exception {
        setUpCluster();

        // A 抢到分发权（通知），B 被集群闸门挡下（本地抑制计数 +1）
        nodeA.onResult("api", 1500, true, true, "GET", "/x", null);
        nodeB.onResult("api", 1500, true, true, "GET", "/x", null);

        // 静默超过抑制窗口后，B 的恢复扫描触发：B 抢到恢复分发权
        Thread.sleep(SUPPRESS_MS + 20);
        nodeB.markRecovered(GovernanceAlertEvent.Type.SLOW_METHOD, "api");
        // A 随后也上报恢复：集群闸门挡下，不再重复发
        nodeA.markRecovered(GovernanceAlertEvent.Type.SLOW_METHOD, "api");

        long recoveries = 0;
        for (GovernanceAlertEvent event : notifierA.events) {
            recoveries += event.isRecovered() ? 1 : 0;
        }
        for (GovernanceAlertEvent event : notifierB.events) {
            recoveries += event.isRecovered() ? 1 : 0;
        }
        assertEquals(1, recoveries, "全集群只应发出一条恢复通知");
        // B 的恢复事件携带本实例被闸门抑制的计数
        GovernanceAlertEvent recovery = notifierB.events.stream()
                .filter(GovernanceAlertEvent::isRecovered).findFirst().orElseThrow();
        assertTrue(recovery.getSuppressedCount() >= 1);
    }

    @Test
    void gateFailureFallsBackToIndependentDispatch() {
        setUpCluster();
        gate.failOpen = true;

        nodeA.onResult("api", 1500, true, true, "GET", "/x", null);
        nodeB.onResult("api", 1500, true, true, "GET", "/x", null);

        // fail-open：闸门不可用时回退为各实例独立分发（与未开启去重一致），告警不丢
        assertEquals(1, notifierA.events.size());
        assertEquals(1, notifierB.events.size());
    }

    @Test
    void nullGateKeepsLegacyBehavior() {
        CollectingNotifier notifierA = new CollectingNotifier();
        CollectingNotifier notifierB = new CollectingNotifier();
        nodeA = new AlertDispatcher(List.of(notifierA), SUPPRESS_MS, 1000, true, null);
        nodeB = new AlertDispatcher(List.of(notifierB), SUPPRESS_MS, 1000, true, null);

        nodeA.onResult("api", 1500, true, true, "GET", "/x", null);
        nodeB.onResult("api", 1500, true, true, "GET", "/x", null);

        // 无闸门：各实例独立分发（0.6.0 行为）
        assertEquals(1, notifierA.events.size());
        assertEquals(1, notifierB.events.size());
    }
}
