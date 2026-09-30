package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.PostgresTestConfiguration;
import ch.benedict.m321.batchwriter.RabbitTestConfiguration;
import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.Container;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.HashSet;
import java.util.Set;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Prüft den vollständigen Weg vom rohen Queue-JSON bis PostgreSQL mit echten Containern. */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class})
class MessageConsumerIntegrationTest {

    private static final Duration QUEUE_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private AmqpAdmin rabbitAdmin;
    @Autowired
    private RabbitListenerEndpointRegistry listenerRegistry;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean
    private BatchWriteService batchWriteService;
    @Autowired
    private PostgreSQLContainer<?> postgresContainer;
    @Autowired
    private RabbitMQContainer rabbitContainer;

    private MessageListenerContainer listener;

    /** Ein gestoppter Consumer erlaubt einen reproduzierbaren Rückstau vor jedem Test. */
    @BeforeEach
    void prepareQueues() {
        listener = listenerRegistry.getListenerContainer("batch-writer");
        assertNotNull(listener);
        listener.stop();
        RabbitConfig configuration = new RabbitConfig();
        Queue persistQueue = configuration.persistQueue();
        Queue deadLetterQueue = configuration.deadLetterQueue();
        rabbitAdmin.declareQueue(persistQueue);
        rabbitAdmin.declareQueue(deadLetterQueue);
        rabbitAdmin.purgeQueue(RabbitConfig.PERSIST_QUEUE);
        rabbitAdmin.purgeQueue(RabbitConfig.DEAD_LETTER_QUEUE);
        jdbcTemplate.update("DELETE FROM message");
    }

    /** Auch bei fehlgeschlagenen Assertions darf kein Consumer in den nächsten Test hineinlaufen. */
    @AfterEach
    void stopConsumer() {
        listener.stop();
    }

    /** Eine einzelne Nachricht darf nicht auf einen vollen Batch warten. */
    @Test
    void persistsSingleMessageWithoutTypeHeader() {
        UUID id = UUID.randomUUID();
        Message message = validMessage(id);
        send(message);

        consumeAndCheck(1, 1, 0);

        String content = jdbcTemplate.queryForObject("SELECT content FROM message WHERE id = ?", String.class, id);
        assertEquals("Hallo", content);
    }

    /** Entspricht dem Queue-Vertrag aus S5: zweimal derselbe Body, nur application/json. */
    @Test
    void storesDuplicateOnlyOnceWithoutDeadLetter() {
        UUID id = UUID.randomUUID();
        Message message = validMessage(id);
        send(message);
        send(message);

        consumeAndCheck(2, 1, 0);
    }

    /** Der Java-Klassenname des fremden Producers darf im Writer bedeutungslos sein. */
    @Test
    void ignoresForeignJavaTypeHeader() {
        UUID id = UUID.randomUUID();
        Message message = validMessage(id);
        MessageProperties properties = message.getMessageProperties();
        properties.setHeader("__TypeId__", "ch.benedict.m321.chatservice.dto.ChatMessage");
        send(message);

        consumeAndCheck(1, 1, 0);
    }

    /** Ein vorhandener Rückstau wird als voller Batch und anschliessender Rest verarbeitet. */
    @Test
    void persistsFullBatchAndRemainder() {
        for (int i = 0; i < 103; i++) {
            UUID id = UUID.randomUUID();
            Message message = validMessage(id);
            send(message);
        }

        consumeAndCheck(103, 103, 0);

        ArgumentCaptor<List<ChatMessage>> batches = ArgumentCaptor.captor();
        verify(batchWriteService, times(2)).saveBatch(batches.capture());
        List<List<ChatMessage>> savedBatches = batches.getAllValues();
        List<ChatMessage> firstBatch = savedBatches.get(0);
        List<ChatMessage> remainder = savedBatches.get(1);
        int firstBatchSize = firstBatch.size();
        int remainderSize = remainder.size();
        assertEquals(100, firstBatchSize);
        assertEquals(3, remainderSize);
    }

    /** Die DLQ erhält nur den fehlerhaften Body; beide gültigen Nachbarn bleiben erhalten. */
    @Test
    void deadLettersInvalidMessageAndPersistsNeighbours() {
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Message first = validMessage(firstId);
        Message invalid = jsonMessage("{}");
        Message second = validMessage(secondId);
        send(first);
        send(invalid);
        send(second);

        consumeAndCheck(3, 2, 1);

        Message deadLetter = rabbitTemplate.receive(RabbitConfig.DEAD_LETTER_QUEUE);
        assertNotNull(deadLetter);
        byte[] body = deadLetter.getBody();
        String json = new String(body, StandardCharsets.UTF_8);
        assertEquals("{}", json);
    }

    /** Ein nicht speicherbarer Zeitpunkt gehört allein in die DLQ, nicht in eine Retry-Schleife. */
    @ParameterizedTest
    @ValueSource(strings = {"+300000-01-01T00:00:00Z", "-4713-12-31T23:59:59.999999Z",
            "+294276-12-31T23:59:59.999999500Z"})
    void deadLettersUnstorableTimestampAndPersistsNeighbours(String timestamp) {
        UUID firstId = UUID.randomUUID();
        UUID invalidId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();
        Message first = validMessage(firstId);
        Message invalid = messageWithTimestamp(invalidId, timestamp);
        Message second = validMessage(secondId);
        send(first);
        send(invalid);
        send(second);

        consumeAndCheck(3, 2, 1);

        List<UUID> storedIds = jdbcTemplate.queryForList("SELECT id FROM message", UUID.class);
        Set<UUID> actualIds = new HashSet<>(storedIds);
        Set<UUID> expectedIds = Set.of(firstId, secondId);
        assertEquals(expectedIds, actualIds);
        Message deadLetter = rabbitTemplate.receive(RabbitConfig.DEAD_LETTER_QUEUE);
        assertNotNull(deadLetter);
        byte[] expectedBody = invalid.getBody();
        byte[] actualBody = deadLetter.getBody();
        assertArrayEquals(expectedBody, actualBody);
    }

    /** Die Reader-Grenzen müssen auch über den echten JDBC-Treiber verlustfrei speicherbar sein. */
    @ParameterizedTest
    @ValueSource(strings = {"-4712-01-01T00:00:00Z", "+294276-12-31T23:59:59.999999Z"})
    void persistsTimestampAtStorageBoundary(String timestamp) {
        UUID id = UUID.randomUUID();
        Message message = messageWithTimestamp(id, timestamp);
        send(message);

        consumeAndCheck(1, 1, 0);

        OffsetDateTime storedTime = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM message WHERE id = ?", OffsetDateTime.class, id);
        assertNotNull(storedTime);
        Instant actualTime = storedTime.toInstant();
        Instant expectedTime = Instant.parse(timestamp);
        assertEquals(expectedTime, actualTime);
    }

    /** Ein vollständig ungültiger Batch darf keine leere DB-Transaktion auslösen. */
    @Test
    void deadLettersInvalidBatchWithoutWriting() {
        Message invalid = jsonMessage("not JSON");
        send(invalid);

        consumeAndCheck(1, 0, 1);

        verifyNoInteractions(batchWriteService);
    }

    /** Eine bereits bestätigte Speicherung bleibt auch bei späterer Wiederzustellung eindeutig. */
    @Test
    void handlesRedeliveryAfterAnEarlierCommit() {
        UUID id = UUID.randomUUID();
        Message message = validMessage(id);
        send(message);
        consumeAndCheck(1, 1, 0);

        send(message);
        consumeAndCheck(1, 1, 0);

        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
        assertEquals(1, count);
    }

    /** S7: derselbe Consumer übersteht 15 Sekunden echten DB-Ausfall mit 300 Nachrichten. */
    @Test
    void recoversAfterDatabaseOutageWithoutWriterRestart() throws Exception {
        listener.start();
        long startedAt = System.nanoTime();
        Set<UUID> expectedIds = sendDuringDatabaseOutage(startedAt);
        awaitDatabaseRecovery(startedAt);
        boolean listenerRunning = listener.isRunning();
        assertTrue(listenerRunning);
        MessageListenerContainer currentListener = listenerRegistry.getListenerContainer("batch-writer");
        assertSame(listener, currentListener);
        List<UUID> storedIds = jdbcTemplate.queryForList("SELECT id FROM message", UUID.class);
        Set<UUID> actualIds = new HashSet<>(storedIds);
        assertEquals(expectedIds, actualIds);
        listener.stop();
        assertQueueSize(RabbitConfig.PERSIST_QUEUE, 0);
        assertQueueSize(RabbitConfig.DEAD_LETTER_QUEUE, 0);
        long totalMillis = elapsedMillis(startedAt);
        assertTrue(totalMillis < 90000, "Recovery must finish within 90 seconds");
        System.out.printf("Database outage test: 300 messages recovered in %d ms, no writer restart%n", totalMillis);
    }

    /** Stoppt nur die Datenbank und stellt sie auch bei fehlgeschlagenen Prüfungen wieder her. */
    private Set<UUID> sendDuringDatabaseOutage(long startedAt) throws Exception {
        DockerClientFactory factory = DockerClientFactory.instance();
        DockerClient dockerClient = factory.client();
        String containerId = postgresContainer.getContainerId();
        try {
            dockerClient.stopContainerCmd(containerId).withTimeout(1).exec();
            Set<UUID> expectedIds = sendOutageMessages();
            await().atMost(QUEUE_TIMEOUT).untilAsserted(() ->
                    verify(batchWriteService, atLeast(2)).saveBatch(anyList()));
            assertRetainedMessagesDuringOutage();
            long elapsedMillis = elapsedMillis(startedAt);
            long remainingOutageMillis = 15000 - elapsedMillis;
            assertTrue(remainingOutageMillis > 0, "Outage checks must finish before database restart");
            Thread.sleep(remainingOutageMillis);
            return expectedIds;
        } finally {
            // Docker-Start erhält denselben Container und dieselbe Portbindung, auch im Fehlerfall.
            dockerClient.startContainerCmd(containerId).exec();
        }
    }

    /** Die aufgezeichneten IDs erlauben später einen vollständigen Vergleich statt nur einer Anzahl. */
    private Set<UUID> sendOutageMessages() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 300; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            Message message = validMessage(id);
            send(message);
        }
        return ids;
    }

    /** Während PostgreSQL hochfährt, darf auch die Prüfverbindung vorübergehend fehlschlagen. */
    private void awaitDatabaseRecovery(long startedAt) {
        long remainingMillis = 90000 - elapsedMillis(startedAt);
        assertTrue(remainingMillis > 0, "Recovery must finish within 90 seconds");
        Duration recoveryTimeout = Duration.ofMillis(remainingMillis);
        await().atMost(recoveryTimeout)
                .ignoreExceptionsMatching(exception -> exception instanceof DataAccessException)
                .untilAsserted(() -> {
                    Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
                    assertEquals(300, count);
                });
    }

    /** Broker-Zähler umfassen sowohl bereite als auch unbestätigte Nachrichten. */
    private void assertRetainedMessagesDuringOutage() throws Exception {
        Container.ExecResult result = rabbitContainer.execInContainer("rabbitmqctl", "-q", "list_queues",
                "name", "messages", "messages_unacknowledged", "--formatter=json");
        int exitCode = result.getExitCode();
        assertEquals(0, exitCode);
        String output = result.getStdout();
        ObjectMapper mapper = new ObjectMapper();
        JsonNode queues = mapper.readTree(output);
        boolean persistQueueFound = false;
        for (JsonNode queue : queues) {
            JsonNode nameField = queue.get("name");
            String name = nameField.asText();
            JsonNode countField = queue.get("messages");
            int count = countField.asInt();
            if (RabbitConfig.PERSIST_QUEUE.equals(name)) {
                assertEquals(300, count, "All messages must remain in RabbitMQ while PostgreSQL is down");
                persistQueueFound = true;
            }
            if (RabbitConfig.DEAD_LETTER_QUEUE.equals(name)) {
                assertEquals(0, count);
            }
        }
        assertTrue(persistQueueFound);
    }

    /** Eine monotone Uhr macht die 15-/90-Sekunden-Grenzen unabhängig von Uhrzeitkorrekturen. */
    private long elapsedMillis(long startedAt) {
        long elapsedNanos = System.nanoTime() - startedAt;
        return elapsedNanos / 1_000_000;
    }

    /** Nach dem Stop wären unbestätigte Lieferungen wieder bereit: die leere Queue belegt ACKs. */
    private void consumeAndCheck(int deliveries, int storedMessages, int deadLetters) {
        await().atMost(QUEUE_TIMEOUT).untilAsserted(() ->
                assertQueueSize(RabbitConfig.PERSIST_QUEUE, deliveries));
        listener.start();
        await().atMost(QUEUE_TIMEOUT).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
            assertEquals(storedMessages, count);
            assertQueueSize(RabbitConfig.PERSIST_QUEUE, 0);
            assertQueueSize(RabbitConfig.DEAD_LETTER_QUEUE, deadLetters);
        });
        listener.stop();
        assertQueueSize(RabbitConfig.PERSIST_QUEUE, 0);
        assertQueueSize(RabbitConfig.DEAD_LETTER_QUEUE, deadLetters);
    }

    /** Prüft bereitliegende Nachrichten, ohne sie durch die Messung zu konsumieren. */
    private void assertQueueSize(String queueName, int expected) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        assertNotNull(information);
        int actual = information.getMessageCount();
        assertEquals(expected, actual);
    }

    /** send statt convertAndSend stellt sicher, dass der Test keine Typheader ergänzt. */
    private void send(Message message) {
        rabbitTemplate.send("", RabbitConfig.PERSIST_QUEUE, message);
    }

    /** Feste Felder und eine frei wählbare ID erlauben gezielte Duplikatprüfungen. */
    private Message validMessage(UUID id) {
        return messageWithTimestamp(id, "2026-09-29T08:00:00Z");
    }

    /** Erlaubt gezielte Zeitgrenzen, ohne die übrigen Vertragsfelder zu verändern. */
    private Message messageWithTimestamp(UUID id, String timestamp) {
        String json = """
                {"id":"%s","roomId":"22222222-2222-4222-8222-222222222222",
                 "senderId":"anna","senderName":"Anna Muster",
                 "content":"Hallo","sentAt":"%s"}
                """.formatted(id, timestamp);
        return jsonMessage(json);
    }

    /** Auf der Leitung stehen ausschliesslich JSON-Bytes und der Content-Type. */
    private Message jsonMessage(String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return new Message(body, properties);
    }
}
