package org.example.apigovernancespringbootstarter.trace;

import io.micrometer.context.ContextSnapshotFactory;
import org.example.apigovernancespringbootstarter.async.spi.AsyncTaskContextPropagator;
import org.example.apigovernancespringbootstarter.config.ApiGovernanceAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Core trace integration shared by HTTP, RPC and messaging boundaries. */
@AutoConfiguration(before = ApiGovernanceAutoConfiguration.class)
@ConditionalOnClass(ContextSnapshotFactory.class)
@ConditionalOnProperty(prefix = "api.governance.tracing", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class ApiGovernanceTracingAutoConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "api.governance.tracing",
            name = "async-context-propagation", havingValue = "true", matchIfMissing = true)
    public AsyncTaskContextPropagator micrometerAsyncTaskContextPropagator() {
        return new MicrometerAsyncTaskContextPropagator();
    }
}
