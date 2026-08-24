package org.example.apigovernancespringbootstarter.async;

import org.example.apigovernancespringbootstarter.async.event.AsyncPhase;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Objects;

/**
 * Caller-thread invocation view supplied to event enrichers.
 *
 * <p>This object may expose arguments, a result and an original error so an
 * enricher can extract a small safe snapshot. It is never submitted to the
 * asynchronous executor and must not be retained by implementations.</p>
 *
 * @since 1.0
 */
public final class AsyncInvocation {

    private final String id;
    private final String action;
    private final AsyncPhase phase;
    private final Class<?> targetClass;
    private final Method method;
    private final Object[] arguments;
    private final Object result;
    private final Throwable error;
    private final Instant startedAt;
    private final long elapsedMillis;

    public AsyncInvocation(String id, String action, AsyncPhase phase,
                           Class<?> targetClass, Method method, Object[] arguments,
                           Object result, Throwable error, Instant startedAt,
                           long elapsedMillis) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
        this.phase = Objects.requireNonNull(phase, "phase must not be null");
        this.targetClass = Objects.requireNonNull(targetClass, "targetClass must not be null");
        this.method = Objects.requireNonNull(method, "method must not be null");
        this.arguments = arguments == null ? new Object[0] : arguments.clone();
        this.result = result;
        this.error = error;
        this.startedAt = Objects.requireNonNull(startedAt, "startedAt must not be null");
        this.elapsedMillis = elapsedMillis;
    }

    public String getId() {
        return id;
    }

    public String getAction() {
        return action;
    }

    public AsyncPhase getPhase() {
        return phase;
    }

    public Class<?> getTargetClass() {
        return targetClass;
    }

    public Method getMethod() {
        return method;
    }

    public Object[] getArguments() {
        return arguments.clone();
    }

    public Object getResult() {
        return result;
    }

    public Throwable getError() {
        return error;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public long getElapsedMillis() {
        return elapsedMillis;
    }
}
