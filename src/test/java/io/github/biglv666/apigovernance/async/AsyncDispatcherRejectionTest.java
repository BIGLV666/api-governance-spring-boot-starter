package io.github.biglv666.apigovernance.async;

import io.github.biglv666.apigovernance.alert.GovernanceAlertEvent;
import io.github.biglv666.apigovernance.alert.GovernanceAlertNotifier;
import io.github.biglv666.apigovernance.alert.internal.AlertDispatcher;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import io.github.biglv666.apigovernance.async.internal.AsyncDispatcher;
import io.github.biglv666.apigovernance.async.internal.AsyncEventFactory;
import io.github.biglv666.apigovernance.async.internal.AsyncHandlerRegistry;
import io.github.biglv666.apigovernance.async.internal.LoggingAsyncHandlerExceptionHandler;
import io.github.biglv666.apigovernance.async.internal.LoggingAsyncTaskRejectionHandler;
import io.github.biglv666.apigovernance.async.internal.NoopAsyncTaskContextPropagator;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutionListener;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 异步分发器拒绝路径测试：队列拒绝时依次触发拒绝处理器、
 * ASYNC_TASK_REJECTED 告警与执行监听器回调（0.5.0 联动链）。
 *
 * @author API Governance Team
 * @since 0.5.0
 */
class AsyncDispatcherRejectionTest {

    /** 立即抛 RejectedExecutionException 的执行器，模拟线程池队列满。 */
    static class RejectingExecutorProvider implements AsyncExecutorProvider {
        @Override
        public java.util.concurrent.Executor getExecutor() {
            return task -> {
                throw new RejectedExecutionException("queue full");
            };
        }
    }

    @Test
    void rejectionTriggersAlertAndListener() throws Exception {
        AtomicInteger rejections = new AtomicInteger();
        List<GovernanceAlertEvent> alerts = new java.util.ArrayList<>();

        try (AnnotationConfigApplicationContext fixture =
                     new AnnotationConfigApplicationContext(AsyncFixtureConfig.class)) {
            AsyncHandlerRegistry registry = new AsyncHandlerRegistry(fixture.getBeanFactory(), false);
            registry.afterSingletonsInstantiated();

            AlertDispatcher alertDispatcher = new AlertDispatcher(
                    List.of((GovernanceAlertNotifier) alerts::add), 0, 1000);
            AsyncDispatcher dispatcher = new AsyncDispatcher(registry, new AsyncEventFactory(List.of()),
                    new RejectingExecutorProvider(), new LoggingAsyncHandlerExceptionHandler(),
                    new LoggingAsyncTaskRejectionHandler(alertDispatcher),
                    new NoopAsyncTaskContextPropagator(),
                    List.of(new AsyncExecutionListener() {
                        @Override
                        public void onRejected(AsyncHandlerInfo handler, AsyncEvent event) {
                            rejections.incrementAndGet();
                        }
                    }));

            dispatcher.dispatch(invocationFor("user.login", "doLogin"));
        }

        assertThat(rejections.get()).isEqualTo(1);
        assertThat(alerts).hasSize(1);
        GovernanceAlertEvent alert = alerts.get(0);
        assertThat(alert.getType()).isEqualTo(GovernanceAlertEvent.Type.ASYNC_TASK_REJECTED);
        assertThat(alert.getApiKey()).isEqualTo("user.login");
        assertThat(alert.getMessage()).contains("queue full");
    }

    private AsyncInvocation invocationFor(String action, String methodName) throws Exception {
        Method method = AsyncFixtureBean.class.getMethod(methodName);
        return new AsyncInvocation("id-1", action, AsyncPhase.AFTER_SUCCESS,
                AsyncFixtureBean.class, method, new Object[0], null, null, Instant.now(), 0L);
    }

    static class AsyncFixtureConfig {
        @org.springframework.context.annotation.Bean
        AsyncFixtureBean asyncFixtureBean() {
            return new AsyncFixtureBean();
        }
    }

    static class AsyncFixtureBean {

        @io.github.biglv666.apigovernance.async.annotation.AsyncAction("user.login")
        public void doLogin() {
        }

        @io.github.biglv666.apigovernance.async.annotation.AsyncHandler(value = "user.login",
                phase = AsyncPhase.AFTER_SUCCESS)
        public void onLogin() {
        }
    }
}
