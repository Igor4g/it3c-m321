package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft die Regeln des Dienstes ohne Broker: wird eine ID vergeben,
 * wird ein Zeitstempel gesetzt, geht genau eine Nachricht raus.
 */
class MessageServiceTest {

    /**
     * Ein Test-Doppel: es merkt sich, was veröffentlicht wurde, statt
     * wirklich zu senden. Bewusst von Hand geschrieben statt mit einem
     * Mock-Framework — so sieht man beim Lesen, was passiert.
     */
    private static class RecordingPublisher extends MessagePublisher {

        private ChatMessage published;
        private int publishCount;

        /** Das Test-Doppel verwendet bewusst keine Netzwerkverbindung. */
        RecordingPublisher() {
            // Das Doppel benutzt das RabbitTemplate nie, deshalb null.
            super(null);
        }

        /** Zeichnet Anzahl und Inhalt auf, statt wirkliche Nachrichten zu veröffentlichen. */
        @Override
        public void publish(ChatMessage message) {
            this.published = message;
            this.publishCount = this.publishCount + 1;
        }
    }

    /** Identität und Zeitpunkt gehören zur serverseitigen Annahme. */
    @Test
    void assignsIdAndTimestamp() {
        RecordingPublisher publisher = new RecordingPublisher();
        MessageTimestamp timestamp = new MessageTimestamp();
        MessageService messageService = new MessageService(publisher, timestamp);
        UUID roomId = UUID.randomUUID();
        SendMessageRequest request =
                new SendMessageRequest(roomId, "anna", "Anna Muster", "Hallo");

        AcceptedResponse response = messageService.accept(request);

        var messageId = response.id();
        assertNotNull(messageId);
        var sentAtResult = response.sentAt();
        assertNotNull(sentAtResult);
    }

    /** Die HTTP-Antwort muss auf genau die eine erzeugte Nachricht verweisen. */
    @Test
    void publishesExactlyOnceWithTheSameId() {
        RecordingPublisher publisher = new RecordingPublisher();
        MessageTimestamp timestamp = new MessageTimestamp();
        MessageService messageService = new MessageService(publisher, timestamp);
        UUID roomId = UUID.randomUUID();
        SendMessageRequest request =
                new SendMessageRequest(roomId, "anna", "Anna Muster", "Hallo");

        AcceptedResponse response = messageService.accept(request);

        assertEquals(1, publisher.publishCount);
        var messageId = response.id();
        var messageId2 = publisher.published.id();
        assertEquals(messageId, messageId2);
        var roomIdResult = publisher.published.roomId();
        assertEquals(roomId, roomIdResult);
        var senderNameResult = publisher.published.senderName();
        assertEquals("Anna Muster", senderNameResult);
        var contentResult = publisher.published.content();
        assertEquals("Hallo", contentResult);
    }

    /** Zwei Annahmen sind unterschiedliche Ereignisse, auch bei gleichem Inhalt. */
    @Test
    void assignsADifferentIdEveryTime() {
        RecordingPublisher publisher = new RecordingPublisher();
        MessageTimestamp timestamp = new MessageTimestamp();
        MessageService messageService = new MessageService(publisher, timestamp);
        var messageId = UUID.randomUUID();
        SendMessageRequest request =
                new SendMessageRequest(messageId, "anna", "Anna Muster", "Hallo");

        AcceptedResponse first = messageService.accept(request);
        AcceptedResponse second = messageService.accept(request);

        assertEquals(2, publisher.publishCount);
        var messageId2 = first.id();
        var messageId3 = second.id();
        assertNotEquals(messageId2, messageId3);
    }
}
