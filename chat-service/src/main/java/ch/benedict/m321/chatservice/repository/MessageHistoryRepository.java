package ch.benedict.m321.chatservice.repository;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/** Liest die gemeinsame Historie; der Batch-Writer bleibt allein für Inserts zuständig. */
@Repository
@RequiredArgsConstructor
public class MessageHistoryRepository {

    private static final String COLUMNS = "id, room_id, sender_id, sender_name, content, sent_at";
    private static final String FIND = "SELECT " + COLUMNS + " FROM message WHERE id = ?";
    private static final String AFTER = "SELECT " + COLUMNS + """
             FROM message
             WHERE room_id = ? AND (sent_at > ? OR (sent_at = ? AND id > ?))
             ORDER BY sent_at ASC, id ASC LIMIT ?
            """;
    private static final String BEFORE_IDS = """
            SELECT id FROM message
            WHERE room_id = ? AND (sent_at < ? OR (sent_at = ? AND id < ?))
            ORDER BY sent_at DESC, id DESC LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    /** Fixiert die Repeatable-Read-Sicht, bevor eine Tabellenabfrage auf eine Sperre warten muss. */
    public void beginSnapshot() {
        jdbcTemplate.queryForObject("SELECT 1", Integer.class);
    }

    /** Nur begrenzte bekannte IDs werden benötigt, nicht der gesamte frühere Nachrichteninhalt. */
    public List<UUID> beforeIds(UUID roomId, ChatMessage cursor, int maximumRows) {
        Instant cursorTime = cursor.sentAt();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(cursorTime, ZoneOffset.UTC);
        UUID cursorId = cursor.id();
        return jdbcTemplate.query(BEFORE_IDS, this::readId,
                roomId, timestamp, timestamp, cursorId, maximumRows);
    }

    /** UUIDs genügen zum späteren Ausschliessen verspäteter alter Broker-Kopien. */
    private UUID readId(ResultSet row, int rowNumber) throws SQLException {
        return row.getObject("id", UUID.class);
    }

    /** Die Cursor-ID kann auch zu einem anderen Raum gehören; diese Prüfung macht der Aufrufer. */
    public ChatMessage find(UUID messageId) {
        List<ChatMessage> rows = jdbcTemplate.query(FIND, this::readMessage, messageId);
        if (rows.isEmpty()) {
            return null;
        }
        return rows.getFirst();
    }

    /** Ein zusätzlicher Datensatz macht das Überschreiten einer Nachlieferungsgrenze erkennbar. */
    public List<ChatMessage> after(UUID roomId, ChatMessage cursor, int maximumRows) {
        Instant cursorTime = cursor.sentAt();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(cursorTime, ZoneOffset.UTC);
        UUID cursorId = cursor.id();
        return jdbcTemplate.query(AFTER, this::readMessage,
                roomId, timestamp, timestamp, cursorId, maximumRows);
    }

    /** Benannte Spalten erhalten den Nachrichtenvertrag unabhängig von der Tabellenreihenfolge. */
    private ChatMessage readMessage(ResultSet row, int rowNumber) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        UUID roomId = row.getObject("room_id", UUID.class);
        String senderId = row.getString("sender_id");
        String senderName = row.getString("sender_name");
        String content = row.getString("content");
        OffsetDateTime timestamp = row.getObject("sent_at", OffsetDateTime.class);
        Instant sentAt = timestamp.toInstant();
        return new ChatMessage(id, roomId, senderId, senderName, content, sentAt);
    }
}
