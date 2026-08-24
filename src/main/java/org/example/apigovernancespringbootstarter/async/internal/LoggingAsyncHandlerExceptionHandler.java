package org.example.apigovernancespringbootstarter.async.internal;

import org.example.apigovernancespringbootstarter.async.AsyncHandlerInfo;
import org.example.apigovernancespringbootstarter.async.event.AsyncEvent;
import org.example.apigovernancespringbootstarter.async.spi.AsyncHandlerExceptionHandler;
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
