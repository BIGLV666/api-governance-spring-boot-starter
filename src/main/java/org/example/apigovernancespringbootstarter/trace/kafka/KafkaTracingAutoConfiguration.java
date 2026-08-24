package org.example.apigovernancespringbootstarter.trace.kafka;

import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/** Automatically enables producer and consumer observations for Spring Kafka. */
@AutoConfiguration(after = org.example.apigovernancespringbootstarter.trace.ApiGovernanceTracingAutoConfiguration.class)
@ConditionalOnClass(KafkaTemplate.class)
@ConditionalOnProperty(prefix = "api.governance.tracing", name = {"enabled", "kafka"},
        havingValue = "true", matchIfMissing = true)
public class KafkaTracingAutoConfiguration {

    @Bean
    public static BeanPostProcessor kafkaObservationBeanPostProcessor() {
        return new KafkaObservationBeanPostProcessor();
    }
}
