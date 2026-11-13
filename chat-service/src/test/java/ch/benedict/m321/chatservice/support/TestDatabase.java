package ch.benedict.m321.chatservice.support;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** Verwendet das echte Writer-Schema und liefert reproduzierbare JDBC-Testdaten. */
public final class TestDatabase {

    /** Reine Testhilfen brauchen kein eigenes Objekt oder Spring-Interface. */
    private TestDatabase() {
    }

    /** Der Testcontainer initialisiert genau das produktive Tabellenschema. */
    public static PostgreSQLContainer<?> create() {
        return new PostgreSQLContainer<>("postgres:16-alpine")
                .withDatabaseName("chat").withUsername("chat").withPassword("chat")
                .withInitScript("schema.sql");
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
