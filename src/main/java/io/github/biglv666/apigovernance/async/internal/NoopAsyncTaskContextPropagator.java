package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.async.spi.AsyncTaskContextPropagator;

/** Default task wrapper used when trace context propagation is unavailable. */
public final class NoopAsyncTaskContextPropagator implements AsyncTaskContextPropagator {

    @Override
    public Runnable wrap(Runnable task) {
        return task;
    }
}
