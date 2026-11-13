package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Der wichtigste Test dieses Dienstes: geht die Nachricht wirklich in
 * BEIDE Wege? Genau das ist der Kern der Entkopplung von Zustellung
 * und Speicherung.
 */
@SpringBootTest
@Testcontainers
class MessagePublisherIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MessagePublisher messagePublisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private ConnectionFactory connectionFactory;

    /**
     * Eine Queue ist gemeinsamer Zustand: sie überlebt die einzelne
     * Testmethode. Ohne dieses Leeren liest der zweite Test die
     * Nachricht des ersten und schlägt scheinbar grundlos fehl.
     */
    @BeforeEach
    void emptyPersistQueue() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
    }

    @Test
    void publishesToPersistQueue() {
        ChatMessage message = createMessage("Hallo Schreibweg");

        messagePublisher.publish(message);

        ChatMessage persisted = receiveFrom(QueueNames.PERSIST_QUEUE);
        assertNotNull(persisted);
        assertEquals(message.id(), persisted.id());
        assertEquals("Hallo Schreibweg", persisted.content());
    }

    @Test
    void publishesToDeliveryExchange() {
        // Wir spielen ein web-gateway: eigene Queue an den Fanout binden.
        Queue gatewayQueue = new AnonymousQueue();
        rabbitAdmin.declareQueue(gatewayQueue);

        FanoutExchange exchange = new FanoutExchange(QueueNames.DELIVERY_EXCHANGE);
        Binding binding = BindingBuilder.bind(gatewayQueue).to(exchange);
        rabbitAdmin.declareBinding(binding);

        ChatMessage message = createMessage("Hallo Zustellweg");

        messagePublisher.publish(message);

        String queueName = gatewayQueue.getName();
        ChatMessage delivered = receiveFrom(queueName);
        assertNotNull(delivered);
        assertEquals(message.id(), delivered.id());
        assertEquals("Hallo Zustellweg", delivered.content());
    }

    /**
     * Holt eine Nachricht aus einer Queue.
     *
     * Der Zieltyp wird hier ausdrücklich mitgegeben. Grund: in der Queue
     * liegt JSON, nicht ein Java-Objekt. Wer liest, muss wissen, was er
     * erwartet — genau so wird es später auch der batch-writer machen,
     * der ja seine eigene Kopie der Klasse hat.
     */
    private ChatMessage receiveFrom(String queueName) {
        ParameterizedTypeReference<ChatMessage> targetType = new ParameterizedTypeReference<>() {
        };
        // Die exklusive Test-Queue gehört der Consumer-Verbindung, nicht dem Publisher.
        RabbitTemplate consumerTemplate = new RabbitTemplate(connectionFactory);
        consumerTemplate.setMessageConverter(rabbitTemplate.getMessageConverter());
        return consumerTemplate.receiveAndConvert(queueName, 5000, targetType);
    }

    /** Baut eine vollständige Nachricht, damit die Tests kurz bleiben. */
    private ChatMessage createMessage(String content) {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", content, sentAt);
    }
}
