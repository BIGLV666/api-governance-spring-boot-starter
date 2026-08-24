package io.github.biglv666.apigovernance.async.spi;

import java.util.concurrent.Executor;

/**
 * Supplies the executor used by asynchronous action handlers.
 *
 * <p>Registering a custom Bean replaces the starter's bounded thread pool.</p>
 *
 * @since 1.0
 */
@FunctionalInterface
public interface AsyncExecutorProvider {

    Executor getExecutor();
}
