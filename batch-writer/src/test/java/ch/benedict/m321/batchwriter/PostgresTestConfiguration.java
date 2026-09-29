package ch.benedict.m321.batchwriter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/** Stellt den Tests eine echte Datenbank mit dem produktiven Schema bereit. */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    /** Spring verwaltet Start und Ende des Containers zusammen mit dem Testkontext. */
    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgresContainer() {
        PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine");
        container.withInitScript("schema.sql");
        return container;
    }
}
