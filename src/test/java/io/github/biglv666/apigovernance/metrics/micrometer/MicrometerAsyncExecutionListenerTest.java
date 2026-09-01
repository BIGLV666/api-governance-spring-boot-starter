package io.github.biglv666.apigovernance.metrics.micrometer;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步执行 Micrometer 桥接监听器测试。
 *
 * @author API Governance Team
 * @since 0.5.0
 */
class MicrometerAsyncExecutionListenerTest {

    private static final String EXECUTIONS = MicrometerAsyncExecutionListener.METRIC_ASYNC_EXECUTIONS;
    private static final String DURATION = MicrometerAsyncExecutionListener.METRIC_ASYNC_DURATION;

    private AsyncEvent event() {
        return new AsyncEvent("id-1", "user.login", AsyncPhase.AFTER_SUCCESS,
                "com.x.Login", "login", Instant.now(), Instant.now(), 5L, null, null);
    }

    private AsyncHandlerInfo handler() {
        return new AsyncHandlerInfo("user.login", AsyncPhase.AFTER_SUCCESS, 100,
                "loginHandlers", "com.x.LoginHandlers", "void onLogin()");
    }

    @Test
    void successAndFailureRecordCounterAndTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerAsyncExecutionListener listener = new MicrometerAsyncExecutionListener(registry, null);

        listener.onSuccess(handler(), event(), 1_000_000L);
        listener.onSuccess(handler(), event(), 2_000_000L);
        listener.onFailure(handler(), event(), 3_000_000L);

        assertThat(registry.counter(EXECUTIONS, "action", "user.login", "outcome", "success").count())
                .isEqualTo(2.0);
        assertThat(registry.counter(EXECUTIONS, "action", "user.login", "outcome", "failure").count())
                .isEqualTo(1.0);
        assertThat(registry.get(DURATION).tag("action", "user.login").tag("outcome", "success").timer().count())
                .isEqualTo(2L);
    }

    @Test
    void rejectionRecordsCounterWithoutTimer() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerAsyncExecutionListener listener = new MicrometerAsyncExecutionListener(registry, null);

        listener.onRejected(handler(), event());

        assertThat(registry.counter(EXECUTIONS, "action", "user.login", "outcome", "rejected").count())
                .isEqualTo(1.0);
        // rejected 只计数，不产生耗时 Timer
        assertThat(registry.find(DURATION).timers()).isEmpty();
    }

    @Test
    void poolGaugesRegisteredForBuiltInExecutor() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.initialize();
        try {
            new MicrometerAsyncExecutionListener(registry, executor);

            assertThat(registry.find("api.governance.async.pool.active").gauge()).isNotNull();
            assertThat(registry.find("api.governance.async.pool.queue.size").gauge()).isNotNull();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void noGaugesForCustomExecutor() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MicrometerAsyncExecutionListener(registry, Runnable::run);

        assertThat(registry.find("api.governance.async.pool.active").gauge()).isNull();
        assertThat(registry.find("api.governance.async.pool.queue.size").gauge()).isNull();
    }
}
