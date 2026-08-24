package io.github.biglv666.apigovernance.async;

import io.github.biglv666.apigovernance.async.event.AsyncError;
import io.github.biglv666.apigovernance.async.event.AsyncEvent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable caller-thread builder exposed to {@code AsyncEventEnricher} plugins.
 *
 * @since 1.0
 */
public final class AsyncEventBuilder {

    private final AsyncInvocation invocation;
    private final Map<String, Object> data = new LinkedHashMap<>();

    public AsyncEventBuilder(AsyncInvocation invocation) {
        this.invocation = invocation;
    }

    /**
     * Adds a snapshot value to the immutable event.
     */
    public AsyncEventBuilder put(String key, Object value) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("event data key must not be blank");
        }
        if (value != null) {
            data.put(key, value);
        }
        return this;
    }

    /**
     * Adds snapshot values to the immutable event.
     */
    public AsyncEventBuilder putAll(Map<String, ?> values) {
        if (values != null) {
            values.forEach(this::put);
        }
        return this;
    }

    /**
     * Builds the immutable cross-thread event.
     */
    public AsyncEvent build() {
        return new AsyncEvent(
                invocation.getId(),
                invocation.getAction(),
                invocation.getPhase(),
                invocation.getTargetClass().getName(),
                invocation.getMethod().getName(),
                invocation.getStartedAt(),
                Instant.now(),
                invocation.getElapsedMillis(),
                AsyncError.from(invocation.getError()),
                data);
    }
}
