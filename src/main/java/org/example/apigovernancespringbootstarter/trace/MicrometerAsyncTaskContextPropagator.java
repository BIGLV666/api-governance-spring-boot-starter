package org.example.apigovernancespringbootstarter.trace;

import io.micrometer.context.ContextSnapshotFactory;
import org.example.apigovernancespringbootstarter.async.spi.AsyncTaskContextPropagator;

/** Propagates Micrometer observation and tracing context to framework workers. */
public final class MicrometerAsyncTaskContextPropagator implements AsyncTaskContextPropagator {

    private final ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();

    @Override
    public Runnable wrap(Runnable task) {
        return snapshotFactory.captureAll().wrap(task);
    }
}
