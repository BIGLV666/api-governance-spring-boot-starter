package org.example.apigovernancespringbootstarter.async.internal;

import org.example.apigovernancespringbootstarter.async.spi.AsyncTaskContextPropagator;

/** Default task wrapper used when trace context propagation is unavailable. */
public final class NoopAsyncTaskContextPropagator implements AsyncTaskContextPropagator {

    @Override
    public Runnable wrap(Runnable task) {
        return task;
    }
}
