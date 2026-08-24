package io.github.biglv666.apigovernance.async.internal;

import io.github.biglv666.apigovernance.async.spi.AsyncExecutorProvider;
import io.github.biglv666.apigovernance.config.ApiGovernanceProperties;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/** Default bounded executor owned by the starter. */
public final class DefaultAsyncExecutorProvider implements AsyncExecutorProvider, DisposableBean {

    private final ThreadPoolTaskExecutor executor;

    public DefaultAsyncExecutorProvider(ApiGovernanceProperties.Async properties) {
        validate(properties);
        executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.getCorePoolSize());
        executor.setMaxPoolSize(properties.getMaxPoolSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setThreadNamePrefix(properties.getThreadNamePrefix());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.getAwaitTerminationSeconds());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
    }

    @Override
    public Executor getExecutor() {
        return executor;
    }

    @Override
    public void destroy() {
        executor.shutdown();
    }

    private void validate(ApiGovernanceProperties.Async properties) {
        if (properties.getCorePoolSize() < 1) {
            throw new IllegalArgumentException("api.governance.async.core-pool-size must be at least 1");
        }
        if (properties.getMaxPoolSize() < properties.getCorePoolSize()) {
            throw new IllegalArgumentException(
                    "api.governance.async.max-pool-size must be greater than or equal to core-pool-size");
        }
        if (properties.getQueueCapacity() < 0) {
            throw new IllegalArgumentException("api.governance.async.queue-capacity must not be negative");
        }
        if (properties.getKeepAliveSeconds() < 0 || properties.getAwaitTerminationSeconds() < 0) {
            throw new IllegalArgumentException("async timeout properties must not be negative");
        }
        if (properties.getThreadNamePrefix() == null || properties.getThreadNamePrefix().isBlank()) {
            throw new IllegalArgumentException("api.governance.async.thread-name-prefix must not be blank");
        }
    }
}
