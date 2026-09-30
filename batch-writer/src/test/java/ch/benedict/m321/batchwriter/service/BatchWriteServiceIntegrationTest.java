package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.PostgresTestConfiguration;
import ch.benedict.m321.batchwriter.RabbitTestConfiguration;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doAnswer;

/** Prüft den Schreibweg samt echter Transaktion ohne eine umschliessende Testtransaktion. */
@SpringBootTest
@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class})
class BatchWriteServiceIntegrationTest {

    @Autowired
    private BatchWriteService batchWriteService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoSpyBean
    private MessageRepository messageRepository;

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

        assertMessageCount(2);
        assertStoredMessage(first);
        assertStoredMessage(second);
    }

    /** Auch zwei gleiche IDs in derselben Lieferung dürfen keine zweite Zeile erzeugen. */
    @Test
    void ignoresDuplicateWithinBatch() {
        ChatMessage message = createMessage("Original");
        List<ChatMessage> messages = List.of(message, message);

        batchWriteService.saveBatch(messages);

        assertMessageCount(1);
        assertStoredMessage(message);
    }

    /** Eine Wiederzustellung darf den zuerst gespeicherten Inhalt nicht überschreiben. */
    @Test
    void preservesExistingMessageOnRedelivery() {
        ChatMessage original = createMessage("Original");
        UUID originalId = original.id();
        UUID otherRoomId = UUID.randomUUID();
        Instant changedTime = Instant.parse("2026-10-01T12:00:00Z");
        ChatMessage changed = new ChatMessage(originalId, otherRoomId,
                "other", "Other Sender", "Changed", changedTime);
        ChatMessage next = createMessage("Next message");
        List<ChatMessage> firstBatch = List.of(original);
        List<ChatMessage> secondBatch = List.of(changed, next);

        batchWriteService.saveBatch(firstBatch);
        batchWriteService.saveBatch(secondBatch);

        assertMessageCount(2);
        assertStoredMessage(original);
        assertStoredMessage(next);
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

        assertMessageCount(1);
        assertStoredMessage(existing);
        List<ChatMessage> retryBatch = List.of(valid);
        batchWriteService.saveBatch(retryBatch);
        assertMessageCount(2);
        assertStoredMessage(valid);
    }

    /** Auch nach erfolgreichem SQL muss ein Fehler vor COMMIT alle neuen Zeilen zurückrollen. */
    @Test
    void rollsBackWhenFailureOccursAfterJdbcBatch() {
        ChatMessage first = createMessage("First uncommitted message");
        ChatMessage second = createMessage("Second uncommitted message");
        List<ChatMessage> messages = List.of(first, second);
        DataAccessResourceFailureException failure =
                new DataAccessResourceFailureException("Failure before commit");
        doAnswer(invocation -> {
            invocation.callRealMethod();
            // Der JDBC-Batch ist fertig, aber die Service-Transaktion noch nicht bestätigt.
            throw failure;
        }).when(messageRepository).saveBatch(messages);

        assertThrows(DataAccessResourceFailureException.class,
                () -> batchWriteService.saveBatch(messages));

        assertMessageCount(0);
    }

    /** Eine leere Lieferung benötigt keine Schreiboperation. */
    @Test
    void acceptsEmptyBatch() {
        List<ChatMessage> messages = List.of();

        batchWriteService.saveBatch(messages);

        assertMessageCount(0);
    }

    /** Mikrosekunden sind in Java und PostgreSQL ohne Rundungsverlust vergleichbar. */
    private ChatMessage createMessage(String content) {
        UUID id = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.parse("2026-09-29T10:15:30.123456Z");
        return new ChatMessage(id, roomId, "anna", "Anna Muster", content, sentAt);
    }

    /** Vergleicht die nach COMMIT oder ROLLBACK tatsächlich sichtbare Zeilenzahl. */
    private void assertMessageCount(int expected) {
        Integer actual = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
        assertEquals(expected, actual);
    }

    /** Jeder Vergleich umfasst alle sechs Felder, ohne mehrstufige Aufrufe im Testablauf. */
    private void assertStoredMessage(ChatMessage expected) {
        UUID id = expected.id();
        ChatMessage actual = readMessage(id);
        assertEquals(expected, actual);
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
