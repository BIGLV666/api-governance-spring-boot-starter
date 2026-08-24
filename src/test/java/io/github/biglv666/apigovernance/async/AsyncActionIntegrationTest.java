package io.github.biglv666.apigovernance.async;

import io.github.biglv666.apigovernance.async.annotation.AsyncAction;
import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.event.AsyncPhase;
import io.github.biglv666.apigovernance.async.spi.AsyncEventEnricher;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.async.spi.AsyncHandlerExceptionHandler;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskRejectionHandler;
import io.github.biglv666.apigovernance.config.ApiGovernanceAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncActionIntegrationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ApiGovernanceAutoConfiguration.class))
            .withUserConfiguration(TestConfiguration.class);

    @Test
    void dispatchesAllLifecyclePhasesAndPreservesBusinessResult() {
        runner.run(context -> {
            ActionService service = context.getBean(ActionService.class);
            HandlerRecorder recorder = context.getBean(HandlerRecorder.class);

            assertThat(service.success("alice")).isEqualTo("hello alice");

            assertThat(recorder.events)
                    .extracting(AsyncEvent::phase)
                    .containsExactly(AsyncPhase.BEFORE, AsyncPhase.AFTER_SUCCESS,
                            AsyncPhase.AFTER_COMPLETION);
            AsyncEvent success = recorder.events.get(1);
            assertThat(success.action()).isEqualTo("user.login");
            assertThat(success.data()).containsEntry("username", "alice")
                    .containsEntry("resultType", "String");
            assertThat(success.error()).isNull();
            assertThat(success.id()).isEqualTo(recorder.events.get(0).id());
        });
    }

    @Test
    void preservesOriginalErrorAndPublishesErrorSummary() {
        runner.run(context -> {
            ActionService service = context.getBean(ActionService.class);
            HandlerRecorder recorder = context.getBean(HandlerRecorder.class);

            assertThatThrownBy(service::failure)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("invalid login");

            assertThat(recorder.events)
                    .extracting(AsyncEvent::phase)
                    .containsExactly(AsyncPhase.BEFORE, AsyncPhase.AFTER_ERROR,
                            AsyncPhase.AFTER_COMPLETION);
            AsyncEvent error = recorder.events.get(1);
            assertThat(error.error().type()).isEqualTo(IllegalArgumentException.class.getName());
            assertThat(error.error().message()).isEqualTo("invalid login");
        });
    }

    @Test
    void submitsHandlersByOrderAndRoutesHandlerFailures() {
        runner.run(context -> {
            ActionService service = context.getBean(ActionService.class);
            HandlerRecorder recorder = context.getBean(HandlerRecorder.class);

            assertThat(service.ordered()).isEqualTo("ok");

            assertThat(recorder.order).containsExactly(100, 200, 300);
            assertThat(recorder.handlerErrors).hasSize(1);
            assertThat(recorder.handlerErrors.get(0).getMessage()).isEqualTo("handler failed");
        });
    }

    @Test
    void rejectedTasksDoNotAlterBusinessResult() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ApiGovernanceAutoConfiguration.class))
                .withUserConfiguration(RejectedTaskConfiguration.class)
                .run(context -> {
                    ActionService service = context.getBean(ActionService.class);
                    RejectionRecorder recorder = context.getBean(RejectionRecorder.class);

                    assertThat(service.success("alice")).isEqualTo("hello alice");
                    assertThat(recorder.events)
                            .extracting(AsyncEvent::phase)
                            .containsExactly(AsyncPhase.BEFORE, AsyncPhase.AFTER_SUCCESS,
                                    AsyncPhase.AFTER_COMPLETION);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TestConfiguration {

        @Bean
        AsyncExecutorProvider directExecutorProvider() {
            return () -> Runnable::run;
        }

        @Bean
        HandlerRecorder handlerRecorder() {
            return new HandlerRecorder();
        }

        @Bean
        ActionService actionService() {
            return new ActionService();
        }

        @Bean
        AsyncEventEnricher testEnricher() {
            return (builder, invocation) -> {
                if (invocation.getArguments().length > 0) {
                    builder.put("username", invocation.getArguments()[0]);
                }
                if (invocation.getResult() != null) {
                    builder.put("resultType", invocation.getResult().getClass().getSimpleName());
                }
            };
        }

        @Bean
        AsyncHandlerExceptionHandler testExceptionHandler(HandlerRecorder recorder) {
            return (event, handler, error) -> recorder.handlerErrors.add(error);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class RejectedTaskConfiguration {

        @Bean
        AsyncExecutorProvider rejectingExecutorProvider() {
            return () -> command -> {
                throw new RejectedExecutionException("full");
            };
        }

        @Bean
        ActionService rejectedActionService() {
            return new ActionService();
        }

        @Bean
        HandlerRecorder rejectedHandlerRecorder() {
            return new HandlerRecorder();
        }

        @Bean
        RejectionRecorder rejectionRecorder() {
            return new RejectionRecorder();
        }

        @Bean
        AsyncTaskRejectionHandler testRejectionHandler(RejectionRecorder recorder) {
            return (event, handler, error) -> recorder.events.add(event);
        }
    }

    public static class ActionService {

        @AsyncAction("user.login")
        public String success(String username) {
            return "hello " + username;
        }

        @AsyncAction("user.login")
        public String failure() {
            throw new IllegalArgumentException("invalid login");
        }

        @AsyncAction("ordered.action")
        public String ordered() {
            return "ok";
        }
    }

    public static class HandlerRecorder {

        private final List<AsyncEvent> events = new CopyOnWriteArrayList<>();
        private final List<Integer> order = new ArrayList<>();
        private final List<Throwable> handlerErrors = new ArrayList<>();

        @AsyncHandler(value = "user.login", phase = AsyncPhase.BEFORE)
        public void before(AsyncEvent event) {
            events.add(event);
        }

        @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_SUCCESS)
        public void success(AsyncEvent event) {
            events.add(event);
        }

        @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_ERROR)
        public void error(AsyncEvent event) {
            events.add(event);
        }

        @AsyncHandler(value = "user.login", phase = AsyncPhase.AFTER_COMPLETION)
        public void completion(AsyncEvent event) {
            events.add(event);
        }

        @AsyncHandler(value = "ordered.action", order = 300)
        public void third() {
            order.add(300);
        }

        @AsyncHandler(value = "ordered.action", order = 100)
        public void first() {
            order.add(100);
        }

        @AsyncHandler(value = "ordered.action", order = 200)
        public void second() {
            order.add(200);
            throw new IllegalStateException("handler failed");
        }
    }

    public static class RejectionRecorder {

        private final List<AsyncEvent> events = new ArrayList<>();
    }
}
