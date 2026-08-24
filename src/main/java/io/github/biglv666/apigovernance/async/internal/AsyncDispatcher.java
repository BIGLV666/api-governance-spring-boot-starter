package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.AsyncInvocation;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
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

    public AsyncDispatcher(AsyncHandlerRegistry registry,
                           AsyncEventFactory eventFactory,
                           AsyncExecutorProvider executorProvider,
                           AsyncHandlerExceptionHandler exceptionHandler,
                           AsyncTaskRejectionHandler rejectionHandler,
                           AsyncTaskContextPropagator contextPropagator) {
        this.registry = registry;
        this.eventFactory = eventFactory;
        this.executor = executorProvider.getExecutor();
        this.exceptionHandler = exceptionHandler;
        this.rejectionHandler = rejectionHandler;
        this.contextPropagator = contextPropagator;
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
        }
    }

    private void invoke(AsyncEvent event, RegisteredAsyncHandler handler) {
        try {
            handler.invoke(event);
        } catch (Throwable error) {
            try {
                exceptionHandler.handle(event, handler.info(), error);
            } catch (Throwable callbackError) {
                log.error("Async exception handler failed: action={}, handler={}",
                        event.action(), handler.info().method(), callbackError);
            }
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
}
