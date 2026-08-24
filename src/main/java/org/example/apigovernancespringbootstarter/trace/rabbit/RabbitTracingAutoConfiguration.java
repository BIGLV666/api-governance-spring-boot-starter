package org.example.apigovernancespringbootstarter.trace.rabbit;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/** Automatically enables producer and consumer observations for Spring AMQP. */
@AutoConfiguration(after = org.example.apigovernancespringbootstarter.trace.ApiGovernanceTracingAutoConfiguration.class)
@ConditionalOnClass(RabbitTemplate.class)
@ConditionalOnProperty(prefix = "api.governance.tracing", name = {"enabled", "rabbit"},
        havingValue = "true", matchIfMissing = true)
public class RabbitTracingAutoConfiguration {

    @Bean
    public static BeanPostProcessor rabbitObservationBeanPostProcessor() {
        return new RabbitObservationBeanPostProcessor();
    }
}
