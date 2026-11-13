package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Die Regeln des chat-service.
 *
 * Hier — und nur hier — bekommt eine Nachricht ihre Identität und ihre
 * Zeit. Beides vergibt der Server, damit alle Empfänger dieselbe
 * Reihenfolge und dieselbe ID sehen.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageService {

    private final MessagePublisher messagePublisher;
    private final MessageTimestamp messageTimestamp;

    /**
     * Nimmt eine Nachricht an und gibt zurück, unter welcher ID sie im
     * System unterwegs ist.
     *
     * Der Rückgabewert bedeutet ausdrücklich NICHT "gespeichert" — die
     * Nachricht liegt zu diesem Zeitpunkt erst in der Queue.
     */
    public synchronized AcceptedResponse accept(SendMessageRequest request) {
        String content = request.content();
        int contentLength = content.length();
        UUID roomId = request.roomId();
        String senderId = request.senderId();
        String senderName = request.senderName();
        log.info("Message received for room {} with {} characters", roomId, contentLength);

        UUID messageId = UUID.randomUUID();
        Instant sentAt = messageTimestamp.next();

        ChatMessage message = new ChatMessage(
                messageId,
                roomId,
                senderId,
                senderName,
                content,
                sentAt);

        messagePublisher.publish(message);

        return new AcceptedResponse(messageId, sentAt);
    }
}
