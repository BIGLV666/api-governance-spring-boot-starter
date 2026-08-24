package io.github.biglv666.apigovernance.async;

import io.github.biglv666.apigovernance.async.annotation.AsyncHandler;
import io.github.biglv666.apigovernance.config.ApiGovernanceAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncHandlerValidationTest {

    @Test
    void rejectsInvalidHandlerSignatureAtStartup() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ApiGovernanceAutoConfiguration.class))
                .withUserConfiguration(InvalidConfiguration.class)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure()
                        .hasMessage("@AsyncHandler method must return void: "
                                + "bean='invalidHandler', method='public java.lang.String "
                                + "io.github.biglv666.apigovernance.async."
                                + "AsyncHandlerValidationTest$InvalidHandler.handle()'"));
    }

    @Configuration(proxyBeanMethods = false)
    static class InvalidConfiguration {

        @Bean
        InvalidHandler invalidHandler() {
            return new InvalidHandler();
        }
    }

    public static class InvalidHandler {

        @AsyncHandler("invalid")
        public String handle() {
            return "invalid";
        }
    }
}
