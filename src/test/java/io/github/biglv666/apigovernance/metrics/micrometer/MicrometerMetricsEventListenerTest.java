package io.github.biglv666.apigovernance.metrics.micrometer;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Micrometer 桥接监听器单元测试：验证治理事件正确映射为标准指标。
 *
 * @author API Governance Team
 * @since 0.2.0
 */
class MicrometerMetricsEventListenerTest {

    private SimpleMeterRegistry meterRegistry;
    private MicrometerMetricsEventListener listener;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        listener = new MicrometerMetricsEventListener(meterRegistry);
    }

    @Test
    void resultEventIncrementsCounterWithOutcomeTags() {
        listener.onResult("com.x.UserController#get", 120, true, false, "GET", "/u", null);
        listener.onResult("com.x.UserController#get", 150, false, false, "GET", "/u", "boom");

        assertEquals(1.0, meterRegistry.counter("api.governance.requests",
                "api", "com.x.UserController#get", "method", "GET", "outcome", "success").count());
        assertEquals(1.0, meterRegistry.counter("api.governance.requests",
                "api", "com.x.UserController#get", "method", "GET", "outcome", "error").count());
    }

    @Test
    void resultEventRecordsTimer() {
        listener.onResult("com.x.OrderController#create", 250, true, false, "POST", "/o", null);

        var timer = meterRegistry.timer("api.governance.request.duration",
                "api", "com.x.OrderController#create", "method", "POST");
        assertEquals(1, timer.count());
        assertEquals(250_000_000.0, timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS), 1.0);
    }

    @Test
    void rejectEventIncrementsCounterWithRejectOutcome() {
        listener.onReject("com.x.UserController#get", "GET", "/u", "too many");

        assertEquals(1.0, meterRegistry.counter("api.governance.requests",
                "api", "com.x.UserController#get", "method", "GET", "outcome", "reject").count());
    }

    @Test
    void nullHttpMethodNormalizedToUnknown() {
        listener.onResult("api", 10, true, false, null, null, null);

        assertNotNull(meterRegistry.counter("api.governance.requests",
                "api", "api", "method", "unknown", "outcome", "success"));
    }

    @Test
    void nullRegistryIsNoOp() {
        MicrometerMetricsEventListener noOp = new MicrometerMetricsEventListener(null);
        // 不抛异常即可
        noOp.onResult("api", 10, true, false, "GET", "/", null);
        noOp.onReject("api", "GET", "/", "reason");
        assertNull(null);
    }
}
