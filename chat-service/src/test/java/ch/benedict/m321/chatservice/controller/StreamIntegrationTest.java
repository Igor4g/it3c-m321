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

    // Gleiche Testfristen werden gemeinsam benannt statt pro Aufruf wiederholt.
    private static final Duration SECONDS_5 = Duration.ofSeconds(5);
    private static final Duration MILLISECONDS_200 = Duration.ofMillis(200);


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
            List<ChatMessage> received = recipient.take(1, SECONDS_5);
            var receivedMessage = received.getFirst();
            assertEquals(expected, receivedMessage);
            recipient.assertQuiet(MILLISECONDS_200);
            other.assertQuiet(MILLISECONDS_200);
        }
    }

    /** Fehlende, ungültige und verkürzte UUIDs eröffnen keine Verbindung. */
    @Test
    void rejectsInvalidRoomParameters() throws Exception {
        try (TestChat application = new TestChat(broker)) {
            var response = application.get("/stream", null);
            var statusCode2 = response.statusCode();
            assertEquals(400, statusCode2);
            var response2 = application.get("/stream?roomId=wrong", null);
            var statusCode3 = response2.statusCode();
            assertEquals(400, statusCode3);
            var response3 = application.get("/stream?roomId=1-1-1-1-1", null);
            var statusCode4 = response3.statusCode();
            assertEquals(400, statusCode4);
        }
    }

    /** Ein Schreibversuch erkennt den TCP-Abbruch und entfernt die betroffene Registrierung. */
    @Test
    void removesDisconnectedClients() throws Exception {
        try (TestChat application = new TestChat(broker)) {
            StreamService streams = application.context.getBean(StreamService.class);
            UUID room = UUID.randomUUID();
            SseClient recipient = new SseClient(application, room, null);
            var connectionCount2 = streams.connectionCount();
            assertEquals(1, connectionCount2);
            recipient.close();
            var readiness = await();
            var boundedWait = readiness.atMost(SECONDS_5);
            boundedWait.untilAsserted(() -> {
                application.send(room, "cleanup", "After disconnect");
                var connectionCount3 = streams.connectionCount();
                assertEquals(0, connectionCount3);
            });
        }
    }
}
