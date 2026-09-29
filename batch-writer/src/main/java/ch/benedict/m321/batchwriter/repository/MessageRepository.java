package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Kapselt den SQL-Schreibweg; vorhandene IDs bleiben bei Wiederzustellung unverändert. */
@Repository
@RequiredArgsConstructor
public class MessageRepository {

    private static final String INSERT_MESSAGE = """
            INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /** Führt die Inserts als JDBC-Batch innerhalb der Transaktion des Services aus. */
    public void saveBatch(List<ChatMessage> messages) {
        int batchSize = messages.size();
        jdbcTemplate.batchUpdate(INSERT_MESSAGE, messages, batchSize, this::bindMessage);
    }

    /** Parameterbindung trennt Chat-Inhalte vom SQL und erhält die ursprünglichen Feldwerte. */
    private void bindMessage(PreparedStatement statement, ChatMessage message) throws SQLException {
        OffsetDateTime sentAt = OffsetDateTime.ofInstant(message.sentAt(), ZoneOffset.UTC);
        statement.setObject(1, message.id());
        statement.setObject(2, message.roomId());
        statement.setString(3, message.senderId());
        statement.setString(4, message.senderName());
        statement.setString(5, message.content());
        statement.setObject(6, sentAt);
    }
}
