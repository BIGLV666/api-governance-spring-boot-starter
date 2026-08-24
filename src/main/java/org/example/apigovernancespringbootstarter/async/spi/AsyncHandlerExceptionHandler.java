package org.example.apigovernancespringbootstarter.async.spi;

import org.example.apigovernancespringbootstarter.async.AsyncHandlerInfo;
import org.example.apigovernancespringbootstarter.async.event.AsyncEvent;

/**
 * Handles errors thrown by asynchronous handler methods.
 *
 * @since 1.0
 */
@FunctionalInterface
public interface AsyncHandlerExceptionHandler {

    void handle(AsyncEvent event, AsyncHandlerInfo handler, Throwable error);
}
