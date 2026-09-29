package ch.benedict.m321.batchwriter;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;

/** Gibt den Writer-Tests einen echten Broker statt einer versteckten lokalen Voraussetzung. */
@TestConfiguration(proxyBeanMethods = false)
public class RabbitTestConfiguration {

    /** Spring verbindet den Writer mit dem zufälligen Containerport und räumt danach auf. */
    @Bean
    @ServiceConnection
    RabbitMQContainer rabbitContainer() {
        return new RabbitMQContainer("rabbitmq:3.13-management");
    }
}
