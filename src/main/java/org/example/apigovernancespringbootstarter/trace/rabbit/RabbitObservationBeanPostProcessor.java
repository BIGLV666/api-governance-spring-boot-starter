package org.example.apigovernancespringbootstarter.trace.rabbit;

import org.springframework.amqp.rabbit.config.BaseRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ObservableListenerContainer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Enables Spring AMQP's native trace propagation without replacing user beans. */
public final class RabbitObservationBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof RabbitTemplate template) {
            template.setObservationEnabled(true);
        }
        if (bean instanceof BaseRabbitListenerContainerFactory<?> factory) {
            factory.setObservationEnabled(true);
        }
        if (bean instanceof ObservableListenerContainer container) {
            container.setObservationEnabled(true);
        }
        return bean;
    }
}
