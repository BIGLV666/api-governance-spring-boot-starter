package io.github.biglv666.apigovernance.async.spi;

import io.github.biglv666.apigovernance.async.AsyncEventBuilder;
import io.github.biglv666.apigovernance.async.AsyncInvocation;

/**
 * Extracts small, safe values from a caller-thread invocation into an event.
 *
 * <p>Implementations must not retain the invocation, its arguments, result or
 * error because those objects are intentionally excluded from async events.</p>
 *
 * @since 1.0
 */
@FunctionalInterface
public interface AsyncEventEnricher {

    void enrich(AsyncEventBuilder builder, AsyncInvocation invocation);
}
