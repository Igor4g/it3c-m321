package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.PostgresTestConfiguration;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Prüft den Schreibweg samt echter Transaktion ohne eine umschliessende Testtransaktion. */
@SpringBootTest
@Import(PostgresTestConfiguration.class)
class BatchWriteServiceIntegrationTest {

    @Autowired
    private BatchWriteService batchWriteService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Jeder Test beginnt mit einer leeren Tabelle, ohne das Schema auszutauschen. */
    @BeforeEach
    void clearMessages() {
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Nach Rückkehr müssen alle Felder auch ausserhalb der Schreibtransaktion lesbar sein. */
    @Test
    void commitsAllFieldsOfMultipleMessages() {
        ChatMessage first = createMessage("  Grüezi 👋\nNeue Zeile  ");
        ChatMessage second = createMessage("Second message");
        List<ChatMessage> messages = List.of(first, second);

        batchWriteService.saveBatch(messages);

        assertEquals(2, countMessages());
        assertEquals(first, readMessage(first.id()));
        assertEquals(second, readMessage(second.id()));
    }

    /** Auch zwei gleiche IDs in derselben Lieferung dürfen keine zweite Zeile erzeugen. */
    @Test
    void ignoresDuplicateWithinBatch() {
        ChatMessage message = createMessage("Original");
        List<ChatMessage> messages = List.of(message, message);

        batchWriteService.saveBatch(messages);

        assertEquals(1, countMessages());
        assertEquals(message, readMessage(message.id()));
    }

    /** Eine Wiederzustellung darf den zuerst gespeicherten Inhalt nicht überschreiben. */
    @Test
    void preservesExistingMessageOnRedelivery() {
        ChatMessage original = createMessage("Original");
        ChatMessage changed = new ChatMessage(original.id(), UUID.randomUUID(),
                "other", "Other Sender", "Changed", Instant.parse("2026-10-01T12:00:00Z"));
        ChatMessage next = createMessage("Next message");
        List<ChatMessage> firstBatch = List.of(original);
        List<ChatMessage> secondBatch = List.of(changed, next);

        batchWriteService.saveBatch(firstBatch);
        batchWriteService.saveBatch(secondBatch);

        assertEquals(2, countMessages());
        assertEquals(original, readMessage(original.id()));
        assertEquals(next, readMessage(next.id()));
    }

    /** Eine absichtlich ungültige DB-Zeile muss auch vorherige Inserts der Lieferung zurückrollen. */
    @Test
    void rollsBackEntireBatchAndCanSaveAgain() {
        ChatMessage existing = createMessage("Already committed");
        List<ChatMessage> existingBatch = List.of(existing);
        batchWriteService.saveBatch(existingBatch);
        ChatMessage valid = createMessage("Must be rolled back");
        ChatMessage invalid = createMessage(null);
        List<ChatMessage> failedBatch = List.of(valid, invalid);

        assertThrows(DataIntegrityViolationException.class,
                () -> batchWriteService.saveBatch(failedBatch));

        assertEquals(1, countMessages());
        assertEquals(existing, readMessage(existing.id()));
        List<ChatMessage> retryBatch = List.of(valid);
        batchWriteService.saveBatch(retryBatch);
        assertEquals(2, countMessages());
        assertEquals(valid, readMessage(valid.id()));
    }

    /** Eine leere Lieferung benötigt keine Schreiboperation. */
    @Test
    void acceptsEmptyBatch() {
        List<ChatMessage> messages = List.of();

        batchWriteService.saveBatch(messages);

        assertEquals(0, countMessages());
    }

    /** Mikrosekunden sind in Java und PostgreSQL ohne Rundungsverlust vergleichbar. */
    private ChatMessage createMessage(String content) {
        UUID id = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-09-29T10:15:30.123456Z");
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, sentAt);
    }

    /** Zählt die nach COMMIT oder ROLLBACK tatsächlich sichtbaren Zeilen. */
    private int countMessages() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
    }

    /** Liest ausdrücklich alle Felder, damit vertauschte SQL-Parameter auffallen. */
    private ChatMessage readMessage(UUID id) {
        return jdbcTemplate.queryForObject("SELECT * FROM message WHERE id = ?", (result, rowNumber) -> {
            UUID messageId = result.getObject("id", UUID.class);
            UUID roomId = result.getObject("room_id", UUID.class);
            String senderId = result.getString("sender_id");
            String senderName = result.getString("sender_name");
            String content = result.getString("content");
            java.sql.Timestamp timestamp = result.getTimestamp("sent_at");
            Instant sentAt = timestamp.toInstant();
            return new ChatMessage(messageId, roomId, senderId, senderName, content, sentAt);
        }, id);
    }
}
