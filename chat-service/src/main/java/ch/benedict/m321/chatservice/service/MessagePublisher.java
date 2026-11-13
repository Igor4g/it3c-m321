package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Der Weg nach draussen zu RabbitMQ.
 *
 * Jede Nachricht geht in ZWEI Richtungen, und zwar bewusst getrennt:
 * der Zustellweg soll nicht darauf warten, dass jemand die Nachricht
 * in die Datenbank geschrieben hat.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessagePublisher {

    /** Leerer Routing-Key: ein Fanout-Exchange ignoriert ihn ohnehin. */
    private static final String FANOUT_ROUTING_KEY = "";

    private final RabbitTemplate rabbitTemplate;

    /**
     * Legt die Nachricht in den Schreibweg und in den Zustellweg.
     *
     * Erst persist, dann delivery. Die beiden Veröffentlichungen sind nicht
     * atomar: nach einem Fehler kann der erste Weg bereits erfolgreich sein.
     * Eine erfolgreiche Annahme beweist noch keinen Datenbank-COMMIT.
     */
    public void publish(ChatMessage message) {
        rabbitTemplate.convertAndSend(QueueNames.PERSIST_QUEUE, message);
        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, FANOUT_ROUTING_KEY, message);

        var messageId = message.id();
        log.info("Message {} published to queue {} and exchange {}",
                messageId, QueueNames.PERSIST_QUEUE, QueueNames.DELIVERY_EXCHANGE);
    }
}
