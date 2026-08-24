package io.github.biglv666.apigovernance.async.spi;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;

/**
 * Handles tasks rejected by the asynchronous executor.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface AsyncTaskRejectionHandler {

    void handle(AsyncEvent event, AsyncHandlerInfo handler, RuntimeException error);
}
