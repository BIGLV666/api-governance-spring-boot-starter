package org.example.apigovernancespringbootstarter.async.event;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable snapshot delivered to asynchronous handlers.
 *
 * <p>Method arguments, return values, the original exception, AOP join points
 * and web request objects are not captured. Additional safe values may be
 * copied into {@link #data()} by event enrichers on the caller thread.</p>
 *
 * @param id unique identifier shared by all phases of one method invocation
 * @param action logical action name
 * @param phase lifecycle phase
 * @param sourceClass target class name
 * @param sourceMethod target method name
 * @param startedAt target invocation start time
 * @param occurredAt event creation time
 * @param elapsedMillis elapsed target invocation time at event creation
 * @param error immutable error summary for failed phases
 * @param data immutable extension data
 * @since 1.0
 */
public record AsyncEvent(
        String id,
        String action,
        AsyncPhase phase,
        String sourceClass,
        String sourceMethod,
        Instant startedAt,
        Instant occurredAt,
        long elapsedMillis,
        AsyncError error,
        Map<String, Object> data) {

    public AsyncEvent {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(phase, "phase must not be null");
        Objects.requireNonNull(sourceClass, "sourceClass must not be null");
        Objects.requireNonNull(sourceMethod, "sourceMethod must not be null");
        Objects.requireNonNull(startedAt, "startedAt must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        data = data == null ? Map.of() : Map.copyOf(data);
    }
}
