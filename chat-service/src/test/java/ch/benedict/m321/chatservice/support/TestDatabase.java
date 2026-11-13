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
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("chat").withUsername("chat").withPassword("chat")
                .withInitScript("schema.sql");
        // Docker muss beim Ausfalltest denselben Testport erneut binden.
        try (ServerSocket socket = new ServerSocket(0)) {
            int port = socket.getLocalPort();
            container.setPortBindings(List.of(port + ":5432"));
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
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(message.sentAt(), ZoneOffset.UTC);
        jdbc.update("INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at) VALUES (?, ?, ?, ?, ?, ?)",
                message.id(), message.roomId(), message.senderId(), message.senderName(), message.content(), timestamp);
    }
}
