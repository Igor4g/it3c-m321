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
            var isDurableResult = firstQueue.isDurable();
            assertFalse(isDurableResult);
            var isExclusiveResult = firstQueue.isExclusive();
            assertTrue(isExclusiveResult);
            var isAutoDeleteResult = firstQueue.isAutoDelete();
            assertTrue(isAutoDeleteResult);
            var getArgumentsResult = firstQueue.getArguments();
            var response = getArgumentsResult.get("x-max-length");
            assertEquals(999, response);
            var getArgumentsResult2 = firstQueue.getArguments();
            var response2 = getArgumentsResult2.get("x-overflow");
            assertEquals("drop-head", response2);
            var getQueueInfoResult = firstAdmin.getQueueInfo(firstName);
            assertNotNull(getQueueInfoResult);
            var getQueueInfoResult2 = secondAdmin.getQueueInfo(secondName);
            assertNotNull(getQueueInfoResult2);
            assertBound(firstName);
            assertBound(secondName);
            var readiness = await();
            var timeout = Duration.ofSeconds(10);
            var boundedWait = readiness.atMost(timeout);
            boundedWait.until(
                    () -> {
                        var queueInfo = firstAdmin.getQueueInfo(firstName);
                        return queueInfo.getConsumerCount() == 1;
                    });
            var getQueueInfoResult3 = secondAdmin.getQueueInfo(secondName);
            var getConsumerCountResult = getQueueInfoResult3.getConsumerCount();
            assertEquals(1, getConsumerCountResult);

            second.close();
            var readiness2 = await();
            var timeout2 = Duration.ofSeconds(30);
            var boundedWait2 = readiness2.atMost(timeout2);
            boundedWait2.until(
                    () -> firstAdmin.getQueueInfo(secondName) == null);
            var getQueueInfoResult4 = firstAdmin.getQueueInfo(firstName);
            assertNotNull(getQueueInfoResult4);
            var getQueueInfoResult5 = firstAdmin.getQueueInfo(QueueNames.PERSIST_QUEUE);
            assertNotNull(getQueueInfoResult5);
            var getQueueInfoResult6 = firstAdmin.getQueueInfo(QueueNames.DEAD_LETTER_QUEUE);
            assertNotNull(getQueueInfoResult6);
        }
    }

    /** Verbindet einen eigenen Spring-Kontext mit demselben echten Broker. */
    private ConfigurableApplicationContext startInstance() {
        String brokerHost = rabbitMq.getHost();
        int brokerPort = rabbitMq.getAmqpPort();
        var springApplicationBuilder = new SpringApplicationBuilder(ChatServiceApplication.class);
        var webResult = springApplicationBuilder
                .web(WebApplicationType.NONE);
        var propertiesResult = webResult
                .properties("logging.level.root=WARN");
        var getAdminUsernameResult = rabbitMq.getAdminUsername();
        var getAdminPasswordResult = rabbitMq.getAdminPassword();
        return propertiesResult
                .run("--spring.rabbitmq.host=" + brokerHost,
                        "--spring.rabbitmq.port=" + brokerPort,
                        "--spring.rabbitmq.username=" + getAdminUsernameResult,
                        "--spring.rabbitmq.password=" + getAdminPasswordResult,
                        "--logging.level.root=WARN", "--debug=false");
    }

    /** Liest die Broker-Bindungen, statt nur die Spring-Konfiguration anzusehen. */
    private void assertBound(String queueName) throws Exception {
        var result = rabbitMq.execInContainer("rabbitmqctl", "list_bindings",
                "--formatter=json", "source_name", "destination_name");
        var getExitCodeResult = result.getExitCode();
        assertEquals(0, getExitCodeResult);
        ObjectMapper mapper = new ObjectMapper();
        var getStdoutResult = result.getStdout();
        JsonNode bindings = mapper.readTree(getStdoutResult);
        boolean found = false;
        for (JsonNode binding : bindings) {
            var pathResult = binding.path("source_name");
            String source = pathResult.asText();
            var pathResult2 = binding.path("destination_name");
            String destination = pathResult2.asText();
            if (QueueNames.DELIVERY_EXCHANGE.equals(source) && queueName.equals(destination)) {
                found = true;
            }
        }
        assertTrue(found, "The instance queue must be bound to chat.delivery");
    }
}
