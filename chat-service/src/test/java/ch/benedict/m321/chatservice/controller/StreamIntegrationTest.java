package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.StreamService;
import ch.benedict.m321.chatservice.support.SseClient;
import ch.benedict.m321.chatservice.support.TestChat;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Prüft den Stream über echtes HTTP und RabbitMQ, ohne Datenbank oder gemockte Verteilung. */
@Testcontainers
class StreamIntegrationTest {

    @Container
    static RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Alle sechs Felder müssen unverändert und ausschliesslich im ausgewählten Raum ankommen. */
    @Test
    void routesFullJsonOnlyToTheSelectedRoom() throws Exception {
        UUID room = UUID.randomUUID();
        UUID otherRoom = UUID.randomUUID();
        try (TestChat application = new TestChat(broker);
             SseClient recipient = new SseClient(application, room, null);
             SseClient other = new SseClient(application, otherRoom, null)) {
            ChatMessage expected = application.send(room, "anna", "Hallo 👋\nZweite Zeile");
            List<ChatMessage> received = recipient.take(1, Duration.ofSeconds(5));
            assertEquals(expected, received.getFirst());
            recipient.assertQuiet(Duration.ofMillis(200));
            other.assertQuiet(Duration.ofMillis(200));
        }
    }

    /** Fehlende, ungültige und verkürzte UUIDs eröffnen keine Verbindung. */
    @Test
    void rejectsInvalidRoomParameters() throws Exception {
        try (TestChat application = new TestChat(broker)) {
            assertEquals(400, application.get("/stream", null).statusCode());
            assertEquals(400, application.get("/stream?roomId=wrong", null).statusCode());
            assertEquals(400, application.get("/stream?roomId=1-1-1-1-1", null).statusCode());
        }
    }

    /** Ein Schreibversuch erkennt den TCP-Abbruch und entfernt die betroffene Registrierung. */
    @Test
    void removesDisconnectedClients() throws Exception {
        try (TestChat application = new TestChat(broker)) {
            StreamService streams = application.context.getBean(StreamService.class);
            UUID room = UUID.randomUUID();
            SseClient recipient = new SseClient(application, room, null);
            assertEquals(1, streams.connectionCount());
            recipient.close();
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                application.send(room, "cleanup", "After disconnect");
                assertEquals(0, streams.connectionCount());
            });
        }
    }
}
