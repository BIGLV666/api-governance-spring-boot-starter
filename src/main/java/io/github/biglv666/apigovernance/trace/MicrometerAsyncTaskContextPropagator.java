package io.github.biglv666.apigovernance.trace;

import io.micrometer.context.ContextSnapshotFactory;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskContextPropagator;

/** Propagates Micrometer observation and tracing context to framework workers. */
public final class MicrometerAsyncTaskContextPropagator implements AsyncTaskContextPropagator {

    private final ContextSnapshotFactory snapshotFactory = ContextSnapshotFactory.builder().build();

    @Override
    public Runnable wrap(Runnable task) {
        return snapshotFactory.captureAll().wrap(task);
    }
}
