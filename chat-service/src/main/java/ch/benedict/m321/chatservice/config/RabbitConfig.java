package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.amqp.RabbitTemplateConfigurer;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
@EnableConfigurationProperties(LiveProperties.class)
public class RabbitConfig {

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede web-gateway-Instanz jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer WebSocket-Clients
     * im betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /** Jede Instanz braucht ihre eigene Kopie; beim Verbindungsende verschwindet ihre Queue. */
    @Bean
    public Queue liveQueue() {
        Map<String, Object> arguments = Map.of(
                "x-max-length", 999,
                "x-overflow", "drop-head");
        return new AnonymousQueue(arguments);
    }

    /** Bindet nur die kurzlebige Instanz-Queue an den vorhandenen Zustellweg. */
    @Bean
    public Binding liveBinding(Queue liveQueue, FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(liveQueue).to(deliveryExchange);
    }

    /** Ein Consumer und ein unbestätigtes Ereignis erhalten Reihenfolge und Gesamtgrenze. */
    @Bean
    public SimpleRabbitListenerContainerFactory liveListenerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }

    /** Ein eigener Publisher-Kanal erhält die Sendereihenfolge ohne den Consumer zu begrenzen. */
    @Bean
    public RabbitTemplate rabbitTemplate(RabbitTemplateConfigurer configurer,
                                         CachingConnectionFactory connectionFactory) {
        ConnectionFactory publisherConnection = connectionFactory.getPublisherConnectionFactory();
        if (!(publisherConnection instanceof CachingConnectionFactory publisherFactory)) {
            throw new IllegalStateException("A caching publisher connection is required");
        }
        publisherFactory.setChannelCacheSize(1);
        publisherFactory.setChannelCheckoutTimeout(5000);
        RabbitTemplate template = new RabbitTemplate();
        configurer.configure(template, connectionFactory);
        template.setUsePublisherConnection(true);
        return template;
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
