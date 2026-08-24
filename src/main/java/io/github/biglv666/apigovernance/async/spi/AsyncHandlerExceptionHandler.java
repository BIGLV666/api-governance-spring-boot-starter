package io.github.biglv666.apigovernance.async.spi;

import io.github.biglv666.apigovernance.async.AsyncHandlerInfo;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;

/**
 * Handles errors thrown by asynchronous handler methods.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface AsyncHandlerExceptionHandler {

    void handle(AsyncEvent event, AsyncHandlerInfo handler, Throwable error);
}
