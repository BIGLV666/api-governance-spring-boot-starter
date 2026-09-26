package io.github.biglv666.apigovernance.trace;

import io.github.biglv666.apigovernance.trace.internal.ObservationStateReader;
import io.github.biglv666.apigovernance.trace.kafka.KafkaObservationBeanPostProcessor;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQ observation 状态读取与 BeanPostProcessor「尊重宿主配置」语义测试。
 *
 * <p>回归：BPP 曾无条件对所有 KafkaTemplate 调用 {@code setObservationEnabled(true)}，
 * 宿主显式关闭的开关会被静默推翻。
 *
 * @author API Governance Team
 * @since 0.5.1
 */
class ObservationStateReaderTest {

    private final ProducerFactory<String, String> producerFactory = new DefaultKafkaProducerFactory<>(
            Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));

    @Test
    void readsObservationEnabledFieldFromKafkaTemplate() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        // KafkaTemplate 默认不开 observation，且该状态没有公开 getter
        assertThat(ObservationStateReader.readObservationEnabled(template)).isFalse();

        template.setObservationEnabled(true);
        assertThat(ObservationStateReader.readObservationEnabled(template)).isTrue();
    }

    @Test
    void beanPostProcessorRespectsHostExplicitlyEnabledObservation() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);
        template.setObservationEnabled(true);

        new KafkaObservationBeanPostProcessor().postProcessBeforeInitialization(template, "kafkaTemplate");

        assertThat(ObservationStateReader.readObservationEnabled(template)).isTrue();
    }

    @Test
    void beanPostProcessorEnablesObservationForDefaultTemplate() {
        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);

        new KafkaObservationBeanPostProcessor().postProcessBeforeInitialization(template, "kafkaTemplate");

        assertThat(ObservationStateReader.readObservationEnabled(template)).isTrue();
    }

    @Test
    void readerReturnsNullWhenFieldMissing() {
        assertThat(ObservationStateReader.readObservationEnabled(new Object())).isNull();
    }
}
