package io.github.biglv666.apigovernance.trace;

import io.github.biglv666.apigovernance.async.internal.NoopAsyncTaskContextPropagator;
import io.github.biglv666.apigovernance.async.spi.AsyncTaskContextPropagator;
import io.github.biglv666.apigovernance.config.ApiGovernanceAutoConfiguration;
import io.github.biglv666.apigovernance.trace.kafka.KafkaTracingAutoConfiguration;
import io.github.biglv666.apigovernance.trace.rabbit.RabbitTracingAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

class TracingAutoConfigurationTests {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    ApiGovernanceTracingAutoConfiguration.class,
                    KafkaTracingAutoConfiguration.class,
                    RabbitTracingAutoConfiguration.class,
                    ApiGovernanceAutoConfiguration.class));

    @Test
    void traceContextPropagationIsEnabledByDefault() {
        runner.run(context -> assertThat(context.getBean(AsyncTaskContextPropagator.class))
                .isInstanceOf(MicrometerAsyncTaskContextPropagator.class));
    }

    @Test
    void traceContextPropagationCanBeDisabled() {
        runner.withPropertyValues("api.governance.tracing.enabled=false")
                .run(context -> assertThat(context.getBean(AsyncTaskContextPropagator.class))
                        .isInstanceOf(NoopAsyncTaskContextPropagator.class));
    }

    @Test
    void kafkaObservationIsEnabledOnUserBeans() {
        ProducerFactory<Object, Object> producerFactory = mock(ProducerFactory.class);
        KafkaTemplate<Object, Object> template = spy(new KafkaTemplate<>(producerFactory));
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();

        runner.withBean(KafkaTemplate.class, () -> template)
                .withBean(ConcurrentKafkaListenerContainerFactory.class, () -> factory)
                .run(context -> {
                    assertThat(context.getBean(KafkaTemplate.class)).isSameAs(template);
                    verify(template).setObservationEnabled(true);
                    assertThat(factory.getContainerProperties().isObservationEnabled()).isTrue();
                });
    }

    @Test
    void rabbitObservationIsEnabledOnUserBeans() {
        ConnectionFactory connectionFactory = mock(ConnectionFactory.class);
        RabbitTemplate template = spy(new RabbitTemplate(connectionFactory));
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);

        runner.withBean(RabbitTemplate.class, () -> template)
                .withBean(SimpleRabbitListenerContainerFactory.class, () -> factory)
                .run(context -> {
                    assertThat(context.getBean(RabbitTemplate.class)).isSameAs(template);
                    verify(template).setObservationEnabled(true);
                });
    }

    @Test
    void messagingIntegrationsCanBeDisabledIndependently() {
        runner.withPropertyValues(
                        "api.governance.tracing.kafka=false",
                        "api.governance.tracing.rabbit=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("kafkaObservationBeanPostProcessor");
                    assertThat(context).doesNotHaveBean("rabbitObservationBeanPostProcessor");
                });
    }
}
