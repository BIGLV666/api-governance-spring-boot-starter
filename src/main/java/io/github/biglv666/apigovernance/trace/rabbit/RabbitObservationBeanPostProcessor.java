package io.github.biglv666.apigovernance.trace.rabbit;

import io.github.biglv666.apigovernance.trace.internal.ObservationStateReader;
import org.springframework.amqp.rabbit.config.BaseRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.ObservableListenerContainer;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Enables Spring AMQP's native trace propagation without replacing user beans.
 *
 * <p><b>尊重宿主配置</b>：组件当前已开启 observation（例如宿主在代码中对
 * {@code RabbitTemplate} 显式调用了 {@code setObservationEnabled(true)}）时跳过增强，
 * 绝不覆盖宿主显式做出的任何选择。当前状态经 {@link ObservationStateReader} 读取；
 * 状态不可读（字段缺失等）时回退为默认增强，保持既有行为。
 */
public final class RabbitObservationBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof RabbitTemplate template) {
            if (!Boolean.TRUE.equals(ObservationStateReader.readObservationEnabled(template))) {
                template.setObservationEnabled(true);
            }
        }
        if (bean instanceof BaseRabbitListenerContainerFactory<?> factory) {
            if (!Boolean.TRUE.equals(ObservationStateReader.readObservationEnabled(factory))) {
                factory.setObservationEnabled(true);
            }
        }
        if (bean instanceof ObservableListenerContainer container) {
            if (!Boolean.TRUE.equals(ObservationStateReader.readObservationEnabled(container))) {
                container.setObservationEnabled(true);
            }
        }
        return bean;
    }
}
