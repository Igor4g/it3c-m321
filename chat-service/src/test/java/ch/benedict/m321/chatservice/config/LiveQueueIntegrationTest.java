package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Prüft die wirkliche Queue-Lebensdauer zweier voneinander unabhängiger Anwendungen. */
@Testcontainers
class LiveQueueIntegrationTest {

    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Scale-down darf nur die exklusive Queue der beendeten Instanz entfernen. */
    @Test
    void createsIndependentQueuesAndRemovesOnlyTheClosedInstanceQueue() throws Exception {
        try (ConfigurableApplicationContext first = startInstance();
             ConfigurableApplicationContext second = startInstance()) {
            Queue firstQueue = first.getBean("liveQueue", Queue.class);
            Queue secondQueue = second.getBean("liveQueue", Queue.class);
            RabbitAdmin firstAdmin = first.getBean(RabbitAdmin.class);
            RabbitAdmin secondAdmin = second.getBean(RabbitAdmin.class);
            firstAdmin.initialize();
            secondAdmin.initialize();

            String firstName = firstQueue.getName();
            String secondName = secondQueue.getName();
            assertNotEquals(firstName, secondName);
            assertFalse(firstQueue.isDurable());
            assertTrue(firstQueue.isExclusive());
            assertTrue(firstQueue.isAutoDelete());
            assertEquals(999, firstQueue.getArguments().get("x-max-length"));
            assertEquals("drop-head", firstQueue.getArguments().get("x-overflow"));
            assertNotNull(firstAdmin.getQueueInfo(firstName));
            assertNotNull(secondAdmin.getQueueInfo(secondName));
            assertBound(firstName);
            assertBound(secondName);

            second.close();
            await().atMost(Duration.ofSeconds(30)).until(
                    () -> firstAdmin.getQueueInfo(secondName) == null);
            assertNotNull(firstAdmin.getQueueInfo(firstName));
            assertNotNull(firstAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE));
            assertNotNull(firstAdmin.getQueueInfo(QueueNames.DEAD_LETTER_QUEUE));
        }
    }

    /** Verbindet einen eigenen Spring-Kontext mit demselben echten Broker. */
    private ConfigurableApplicationContext startInstance() {
        String brokerHost = rabbitMq.getHost();
        int brokerPort = rabbitMq.getAmqpPort();
        return new SpringApplicationBuilder(ChatServiceApplication.class)
                .web(WebApplicationType.NONE)
                .properties("logging.level.root=WARN")
                .run("--spring.rabbitmq.host=" + brokerHost,
                        "--spring.rabbitmq.port=" + brokerPort,
                        "--spring.rabbitmq.username=" + rabbitMq.getAdminUsername(),
                        "--spring.rabbitmq.password=" + rabbitMq.getAdminPassword());
    }

    /** Liest die Broker-Bindungen, statt nur die Spring-Konfiguration anzusehen. */
    private void assertBound(String queueName) throws Exception {
        var result = rabbitMq.execInContainer("rabbitmqctl", "list_bindings",
                "--formatter=json", "source_name", "destination_name");
        assertEquals(0, result.getExitCode());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode bindings = mapper.readTree(result.getStdout());
        boolean found = false;
        for (JsonNode binding : bindings) {
            String source = binding.path("source_name").asText();
            String destination = binding.path("destination_name").asText();
            if (QueueNames.DELIVERY_EXCHANGE.equals(source) && queueName.equals(destination)) {
                found = true;
            }
        }
        assertTrue(found, "The instance queue must be bound to chat.delivery");
    }
}
