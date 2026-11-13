package ch.benedict.m321.chatservice.support;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

/** Verwendet das echte Writer-Schema und liefert reproduzierbare JDBC-Testdaten. */
public final class TestDatabase {

    /** Reine Testhilfen brauchen kein eigenes Objekt oder Spring-Interface. */
    private TestDatabase() {
    }

    /** Der Testcontainer initialisiert genau das produktive Tabellenschema. */
    public static PostgreSQLContainer<?> create() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container = container.withDatabaseName("chat");
        container = container.withUsername("chat");
        container = container.withPassword("chat");
        container = container.withInitScript("schema.sql");
        // Docker muss beim Ausfalltest denselben Testport erneut binden.
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            List<String> portBindings = List.of(port + ":5432");
            container.setPortBindings(portBindings);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot reserve a test database port", exception);
        }
        return container;
    }

    /** Überschreibt nur Testverbindungen; Timeout-Parameter bleiben für Ausfallprüfungen begrenzt. */
    public static String[] arguments(PostgreSQLContainer<?> database) {
        String url = database.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";
        return new String[]{
                "--spring.datasource.url=" + url + separator + "connectTimeout=2&socketTimeout=5",
                "--spring.datasource.username=" + database.getUsername(),
                "--spring.datasource.password=" + database.getPassword()};
    }

    /** Nur Testvorbereitung schreibt direkt; die produktive Repository-Klasse enthält ausschliesslich SELECT. */
    public static void save(TestChat application, ChatMessage message) {
        JdbcTemplate jdbc = application.context.getBean(JdbcTemplate.class);
        var sentAt = message.sentAt();
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(sentAt, ZoneOffset.UTC);
        var messageId = message.id();
        var roomId = message.roomId();
        String senderId = message.senderId();
        String senderName = message.senderName();
        String content = message.content();
        jdbc.update("INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                messageId, roomId, senderId, senderName, content, timestamp);
    }
}
