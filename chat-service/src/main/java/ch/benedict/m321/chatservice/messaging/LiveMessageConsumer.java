package ch.benedict.m321.chatservice.messaging;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.StreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** Jede Instanz übernimmt ihre Fanout-Kopie und wählt anschliessend ihre lokalen Empfänger. */
@Component
@RequiredArgsConstructor
@Slf4j
public class LiveMessageConsumer {

    private final StreamService streamService;

    /** Ein ACK bestätigt die lokale Übernahme, nicht das Lesen durch alle Benutzer. */
    @RabbitListener(id = "live-delivery", queues = "#{liveQueue.name}", containerFactory = "liveListenerFactory")
    public void receive(ChatMessage message) {
        if (message.id() == null || message.roomId() == null) {
            log.warn("Invalid live message without message or room ID");
            return;
        }
        streamService.receive(message);
    }
}
