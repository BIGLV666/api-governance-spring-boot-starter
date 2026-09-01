package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.AsyncInvocation;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutionListener;
import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.async.spi.AsyncHandlerExceptionHandler;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskRejectionHandler;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskContextPropagator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executor;

/** Submits matching handlers in deterministic order without blocking callers. */
public final class AsyncDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncDispatcher.class);

    private final AsyncHandlerRegistry registry;
    private final AsyncEventFactory eventFactory;
    private final Executor executor;
    private final AsyncHandlerExceptionHandler exceptionHandler;
    private final AsyncTaskRejectionHandler rejectionHandler;
    private final AsyncTaskContextPropagator contextPropagator;
    private final List<AsyncExecutionListener> executionListeners;

    public AsyncDispatcher(AsyncHandlerRegistry registry,
                           AsyncEventFactory eventFactory,
                           AsyncExecutorProvider executorProvider,
                           AsyncHandlerExceptionHandler exceptionHandler,
                           AsyncTaskRejectionHandler rejectionHandler,
                           AsyncTaskContextPropagator contextPropagator) {
        this(registry, eventFactory, executorProvider, exceptionHandler, rejectionHandler,
                contextPropagator, List.of());
    }

    public AsyncDispatcher(AsyncHandlerRegistry registry,
                           AsyncEventFactory eventFactory,
                           AsyncExecutorProvider executorProvider,
                           AsyncHandlerExceptionHandler exceptionHandler,
                           AsyncTaskRejectionHandler rejectionHandler,
                           AsyncTaskContextPropagator contextPropagator,
                           List<AsyncExecutionListener> executionListeners) {
        this.registry = registry;
        this.eventFactory = eventFactory;
        this.executor = executorProvider.getExecutor();
        this.exceptionHandler = exceptionHandler;
        this.rejectionHandler = rejectionHandler;
        this.contextPropagator = contextPropagator;
        this.executionListeners = List.copyOf(executionListeners);
    }

    public void dispatch(AsyncInvocation invocation) {
        List<RegisteredAsyncHandler> handlers = registry.getHandlers(
                invocation.getAction(), invocation.getPhase());
        if (handlers.isEmpty()) {
            return;
        }

        AsyncEvent event;
        try {
            event = eventFactory.create(invocation);
        } catch (Throwable ex) {
            log.error("Failed to create async event: action={}, phase={}",
                    invocation.getAction(), invocation.getPhase(), ex);
            return;
        }

        for (RegisteredAsyncHandler handler : handlers) {
            submit(event, handler);
        }
    }

    private void submit(AsyncEvent event, RegisteredAsyncHandler handler) {
        try {
            executor.execute(contextPropagator.wrap(() -> invoke(event, handler)));
        } catch (RuntimeException ex) {
            invokeRejectionHandler(event, handler.info(), ex);
            notifyListeners(listener -> listener.onRejected(handler.info(), event));
        }
    }

    private void invoke(AsyncEvent event, RegisteredAsyncHandler handler) {
        long startNanos = System.nanoTime();
        try {
            handler.invoke(event);
            long durationNanos = System.nanoTime() - startNanos;
            notifyListeners(listener ->
                    listener.onSuccess(handler.info(), event, durationNanos));
        } catch (Throwable error) {
            long durationNanos = System.nanoTime() - startNanos;
            try {
                exceptionHandler.handle(event, handler.info(), error);
            } catch (Throwable callbackError) {
                log.error("Async exception handler failed: action={}, handler={}",
                        event.action(), handler.info().method(), callbackError);
            }
            long finalDuration = durationNanos;
            notifyListeners(listener ->
                    listener.onFailure(handler.info(), event, finalDuration));
        }
    }

    private void invokeRejectionHandler(AsyncEvent event, AsyncHandlerInfo handler,
                                        RuntimeException error) {
        try {
            rejectionHandler.handle(event, handler, error);
        } catch (Throwable callbackError) {
            log.error("Async rejection handler failed: action={}, handler={}",
                    event.action(), handler.method(), callbackError);
        }
    }

    /** 逐个通知执行监听器并隔离异常：单个监听器故障不影响其他监听器与业务请求。 */
    private void notifyListeners(java.util.function.Consumer<AsyncExecutionListener> action) {
        for (AsyncExecutionListener listener : executionListeners) {
            try {
                action.accept(listener);
            } catch (Exception e) {
                log.warn("Async execution listener failed: listener={}, error={}",
                        listener.getClass().getName(), e.getMessage());
            }
        }
    }
}
