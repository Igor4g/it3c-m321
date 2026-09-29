package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Verbindet den Writer mit den unveränderten Queues des chat-service. */
@Configuration
@EnableConfigurationProperties(BatchProperties.class)
public class RabbitConfig {

    public static final String PERSIST_QUEUE = "chat.persist";
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Die gleichen Queue-Argumente erlauben den gemeinsamen Start beider Dienste. */
    @Bean
    public Queue persistQueue() {
        QueueBuilder builder = QueueBuilder.durable(PERSIST_QUEUE);
        builder.deadLetterExchange("");
        builder.deadLetterRoutingKey(DEAD_LETTER_QUEUE);
        return builder.build();
    }

    /** Dauerhaft ungültige Nachrichten bleiben zur Untersuchung erhalten. */
    @Bean
    public Queue deadLetterQueue() {
        QueueBuilder builder = QueueBuilder.durable(DEAD_LETTER_QUEUE);
        return builder.build();
    }

    /** Spring sammelt begrenzte Batches; nur unser Consumer darf ACK oder NACK senden. */
    @Bean
    public SimpleRabbitListenerContainerFactory batchListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory, BatchProperties properties) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        int batchSize = properties.size();
        long batchTimeout = properties.timeoutMs();
        long receiveTimeout = Math.min(50, batchTimeout);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchListener(true);
        factory.setBatchSize(batchSize);
        factory.setPrefetchCount(batchSize);
        factory.setBatchReceiveTimeout(batchTimeout);
        factory.setReceiveTimeout(receiveTimeout);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        return factory;
    }
}
