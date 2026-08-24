package org.example.apigovernancespringbootstarter.async.spi;

import org.example.apigovernancespringbootstarter.async.AsyncHandlerInfo;
import org.example.apigovernancespringbootstarter.async.event.AsyncEvent;

/**
 * Handles tasks rejected by the asynchronous executor.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface AsyncTaskRejectionHandler {

    void handle(AsyncEvent event, AsyncHandlerInfo handler, RuntimeException error);
}
