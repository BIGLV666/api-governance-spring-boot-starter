package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;
import io.github.biglv666.apigovernance.async.spi.AsyncHandlerExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default handler failure logger. */
public final class LoggingAsyncHandlerExceptionHandler implements AsyncHandlerExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingAsyncHandlerExceptionHandler.class);

    @Override
    public void handle(AsyncEvent event, AsyncHandlerInfo handler, Throwable error) {
        log.error("Async handler failed: eventId={}, action={}, phase={}, bean={}, method={}",
                event.id(), event.action(), event.phase(), handler.beanName(), handler.method(), error);
    }
}
