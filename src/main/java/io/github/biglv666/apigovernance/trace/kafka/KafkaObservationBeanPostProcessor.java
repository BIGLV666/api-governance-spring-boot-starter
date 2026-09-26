package io.github.biglv666.apigovernance.trace.kafka;

import io.github.biglv666.apigovernance.trace.internal.ObservationStateReader;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.kafka.config.AbstractKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.AbstractMessageListenerContainer;

/**
 * Enables Spring Kafka's native trace propagation without replacing user beans.
 *
 * <p><b>尊重宿主配置</b>：组件当前已开启 observation（无论来自宿主显式配置还是其他途径）时
 * 跳过增强，绝不把宿主显式关闭的开关重新打开——例如宿主已设置
 * {@code spring.kafka.template.observation-enabled=false} 时本处理器不再覆盖。
 * 当前状态经 {@link ObservationStateReader} 读取；状态不可读（字段缺失等）时回退为
 * 默认增强，保持既有行为。
 */
public final class KafkaObservationBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof KafkaTemplate<?, ?> template) {
            if (!Boolean.TRUE.equals(ObservationStateReader.readObservationEnabled(template))) {
                template.setObservationEnabled(true);
            }
        }
        if (bean instanceof AbstractKafkaListenerContainerFactory<?, ?, ?> factory) {
            if (!factory.getContainerProperties().isObservationEnabled()) {
                factory.getContainerProperties().setObservationEnabled(true);
            }
        }
        if (bean instanceof AbstractMessageListenerContainer<?, ?> container) {
            if (!container.getContainerProperties().isObservationEnabled()) {
                container.getContainerProperties().setObservationEnabled(true);
            }
        }
        return bean;
    }
}
