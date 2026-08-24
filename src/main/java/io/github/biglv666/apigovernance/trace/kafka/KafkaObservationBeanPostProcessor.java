package io.github.biglv666.apigovernance.trace.kafka;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;

/** Enables Spring Kafka's native trace propagation without replacing user beans. */
public final class KafkaObservationBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof KafkaTemplate<?, ?> template) {
            template.setObservationEnabled(true);
        }
        if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
            factory.getContainerProperties().setObservationEnabled(true);
        }
        if (bean instanceof AbstractMessageListenerContainer<?, ?> container) {
            container.getContainerProperties().setObservationEnabled(true);
        }
        return bean;
    }
}
