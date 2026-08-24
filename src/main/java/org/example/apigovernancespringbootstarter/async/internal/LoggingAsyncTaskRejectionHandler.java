package org.example.apigovernancespringbootstarter.async.internal;

import org.example.apigovernancespringbootstarter.async.AsyncHandlerInfo;
import org.example.apigovernancespringbootstarter.async.event.AsyncEvent;
import org.example.apigovernancespringbootstarter.async.spi.AsyncTaskRejectionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Default rejected task logger. */
public final class LoggingAsyncTaskRejectionHandler implements AsyncTaskRejectionHandler {

    private static final Logger log = LoggerFactory.getLogger(LoggingAsyncTaskRejectionHandler.class);

    @Override
    public void handle(AsyncEvent event, AsyncHandlerInfo handler, RuntimeException error) {
        log.error("Async task rejected: eventId={}, action={}, phase={}, bean={}, method={}",
                event.id(), event.action(), event.phase(), handler.beanName(), handler.method(), error);
    }
}
