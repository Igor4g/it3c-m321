package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.PostgresTestConfiguration;
import ch.benedict.m321.batchwriter.RabbitTestConfiguration;
import ch.benedict.m321.batchwriter.config.RabbitConfig;
import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Prüft den vollständigen Weg vom rohen Queue-JSON bis PostgreSQL mit echten Containern. */
@SpringBootTest(properties = "spring.rabbitmq.listener.simple.auto-startup=false")
@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class})
class MessageConsumerIntegrationTest {

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
        assertEquals(100, firstBatch.size());
        assertEquals(3, remainder.size());
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

    /** Ein vollständig ungültiger Batch darf keine leere DB-Transaktion auslösen. */
    @Test
    void deadLettersInvalidBatchWithoutWriting() {
        Message invalid = jsonMessage("not JSON");
        send(invalid);

        consumeAndCheck(1, 0, 1);

        verifyNoInteractions(batchWriteService);
    }

    /** Nach dem Stop wären unbestätigte Lieferungen wieder bereit: die leere Queue belegt ACKs. */
    private void consumeAndCheck(int deliveries, int storedMessages, int deadLetters) {
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertEquals(deliveries, queueSize(RabbitConfig.PERSIST_QUEUE)));
        listener.start();
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM message", Integer.class);
            assertEquals(storedMessages, count);
            assertEquals(deadLetters, queueSize(RabbitConfig.DEAD_LETTER_QUEUE));
        });
        listener.stop();
        assertEquals(0, queueSize(RabbitConfig.PERSIST_QUEUE));
        assertEquals(deadLetters, queueSize(RabbitConfig.DEAD_LETTER_QUEUE));
    }

    /** Passive Queue-Abfragen zählen bereitliegende Nachrichten ohne sie zu konsumieren. */
    private int queueSize(String queueName) {
        QueueInformation information = rabbitAdmin.getQueueInfo(queueName);
        assertNotNull(information);
        return information.getMessageCount();
    }

    /** send statt convertAndSend stellt sicher, dass der Test keine Typheader ergänzt. */
    private void send(Message message) {
        rabbitTemplate.send("", RabbitConfig.PERSIST_QUEUE, message);
    }

    /** Feste Felder und eine frei wählbare ID erlauben gezielte Duplikatprüfungen. */
    private Message validMessage(UUID id) {
        String json = """
                {"id":"%s","roomId":"22222222-2222-4222-8222-222222222222",
                 "senderId":"anna","senderName":"Anna Muster",
                 "content":"Hallo","sentAt":"2026-09-29T08:00:00Z"}
                """.formatted(id);
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
