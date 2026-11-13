package ch.benedict.m321.chatservice.repository;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.support.TestChat;
import ch.benedict.m321.chatservice.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft echte SQL-Cursor und identische Zeitstempel auf dem Writer-Originalschema. */
@Testcontainers
class MessageHistoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> database = TestDatabase.create();

    @Container
    static RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:3.13-management");

    /** UUIDs oberhalb des Vorzeichenbits müssen wie kanonischer Text und nicht wie Java-long sortieren. */
    @Test
    void readsFieldsAndUsesStableCursorOrder() {
        String[] arguments = TestDatabase.arguments(database);
        try (TestChat application = new TestChat(broker, arguments)) {
            UUID room = UUID.randomUUID();
            Instant time = Instant.parse("2026-11-13T10:00:00.123456Z");
            ChatMessage first = message("00000000-0000-0000-0000-000000000001", room, time, "first");
            ChatMessage second = message("80000000-0000-0000-0000-000000000001", room, time, "second");
            ChatMessage third = message("ffffffff-ffff-ffff-ffff-ffffffffffff", room, time, "third");
            TestDatabase.save(application, third);
            TestDatabase.save(application, first);
            TestDatabase.save(application, second);
            MessageHistoryRepository history = application.context.getBean(MessageHistoryRepository.class);
            assertEquals(first, history.find(first.id()));
            assertNull(history.find(UUID.randomUUID()));
            assertEquals(List.of(second, third), history.after(room, first, 10));
            assertEquals(List.of(second), history.after(room, first, 1));
            List<UUID> predecessors = history.beforeIds(room, third, 10);
            UUID firstId = first.id();
            UUID secondId = second.id();
            List<UUID> expectedPredecessors = List.of(secondId, firstId);
            assertEquals(expectedPredecessors, predecessors);
            List<UUID> limitedPredecessors = history.beforeIds(room, third, 1);
            List<UUID> expectedLimited = List.of(secondId);
            assertEquals(expectedLimited, limitedPredecessors);
            UUID otherRoom = UUID.randomUUID();
            List<UUID> foreignPredecessors = history.beforeIds(otherRoom, third, 10);
            assertTrue(foreignPredecessors.isEmpty());
            assertTrue(history.after(UUID.randomUUID(), first, 10).isEmpty());
        }
    }

    /** Eine SQL-Zeile muss alle sechs Vertragswerte unverändert liefern. */
    private ChatMessage message(String id, UUID room, Instant time, String content) {
        UUID messageId = UUID.fromString(id);
        return new ChatMessage(messageId, room, "sender", "Sender", content, time);
    }
}
