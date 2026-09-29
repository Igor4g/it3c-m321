package ch.benedict.m321.batchwriter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;

/** Stellt den Tests eine echte Datenbank mit dem produktiven Schema bereit. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    /** Spring verwaltet Start und Ende des Containers zusammen mit dem Testkontext. */
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() throws IOException {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.withInitScript("schema.sql");
        int port = findFreePort();
        List<String> bindings = List.of(port + ":5432");
        container.setPortBindings(bindings);
        return container;
    }

    /** Ein expliziter freier Testport bleibt bei Docker-Stop/Start erhalten; kein Produktionsport. */
    private int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
