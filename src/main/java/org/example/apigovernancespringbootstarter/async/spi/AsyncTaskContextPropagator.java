package org.example.apigovernancespringbootstarter.async.spi;

/** Captures caller context and restores it while an asynchronous task runs. */
@FunctionalInterface
public interface AsyncTaskContextPropagator {

    Runnable wrap(Runnable task);
}
