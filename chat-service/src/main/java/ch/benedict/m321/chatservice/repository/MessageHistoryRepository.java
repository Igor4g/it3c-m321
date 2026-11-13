package ch.benedict.m321.chatservice.repository;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
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

    private final JdbcTemplate jdbcTemplate;

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
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(cursor.sentAt(), ZoneOffset.UTC);
        return jdbcTemplate.query(AFTER, this::readMessage,
                roomId, timestamp, timestamp, cursor.id(), maximumRows);
    }

    /** Benannte Spalten erhalten den Nachrichtenvertrag unabhängig von der Tabellenreihenfolge. */
    private ChatMessage readMessage(ResultSet row, int rowNumber) throws SQLException {
        UUID id = row.getObject("id", UUID.class);
        UUID roomId = row.getObject("room_id", UUID.class);
        String senderId = row.getString("sender_id");
        String senderName = row.getString("sender_name");
        String content = row.getString("content");
        OffsetDateTime timestamp = row.getObject("sent_at", OffsetDateTime.class);
        return new ChatMessage(id, roomId, senderId, senderName, content, timestamp.toInstant());
    }
}
