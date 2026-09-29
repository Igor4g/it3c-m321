package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import com.rabbitmq.client.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Verbindet die JSON-Prüfung mit der Speicherung und den Bestätigungen an RabbitMQ. */
@Component
@RequiredArgsConstructor
@Slf4j
public class MessageConsumer {

    private final MessageReader messageReader;
    private final BatchWriteService batchWriteService;

    /** Die rohe AMQP-Nachricht verhindert eine Abhängigkeit von Java-Typheadern des Producers. */
    @RabbitListener(id = "batch-writer", queues = RabbitConfig.PERSIST_QUEUE,
            containerFactory = "batchListenerFactory")
    public void receive(List<Message> deliveries, Channel channel) throws IOException {
        List<ChatMessage> messages = new ArrayList<>();
        List<Long> deliveryTags = new ArrayList<>();
        for (Message delivery : deliveries) {
            readDelivery(delivery, channel, messages, deliveryTags);
        }
        if (messages.isEmpty()) {
            return;
        }

        batchWriteService.saveBatch(messages);
        // Der Schreibservice kehrt erst nach COMMIT zurück. Jeder Tag gehört zu diesem Kanal.
        for (long deliveryTag : deliveryTags) {
            channel.basicAck(deliveryTag, false);
        }
        int batchSize = messages.size();
        log.info("Persisted and acknowledged batch of {} messages", batchSize);
    }

    /** Datenfehler werden einzeln abgelehnt, damit gültige Nachbarn gespeichert werden können. */
    private void readDelivery(Message delivery, Channel channel, List<ChatMessage> messages,
            List<Long> deliveryTags) throws IOException {
        MessageProperties properties = delivery.getMessageProperties();
        long deliveryTag = properties.getDeliveryTag();
        String contentType = properties.getContentType();
        byte[] body = delivery.getBody();
        try {
            ChatMessage message = messageReader.read(body, contentType);
            messages.add(message);
            deliveryTags.add(deliveryTag);
        } catch (InvalidMessageException exception) {
            channel.basicNack(deliveryTag, false, false);
            String reason = exception.getMessage();
            log.warn("Rejected invalid message with delivery tag {}: {}", deliveryTag, reason);
        }
    }
}
