package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.config.BatchProperties;
import ch.benedict.m321.batchwriter.service.BatchWriteService;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionSystemException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/** Prüft die Reihenfolge von Speichern und Bestätigen unabhängig von Netzwerk-Timing. */
class MessageConsumerTest {

    private final MessageReader messageReader = new MessageReader();
    private final BatchWriteService batchWriteService = mock(BatchWriteService.class);
    private final Channel channel = mock(Channel.class);
    private final BatchProperties properties = new BatchProperties(100, 200, 1);
    private final MessageConsumer consumer = new MessageConsumer(messageReader, batchWriteService, properties);

    /** Der Service muss vor dem ersten ACK erfolgreich zurückkehren. */
    @Test
    void savesBeforeAcknowledgingEachDelivery() throws IOException {
        Message first = message(11, validJson());
        Message second = message(12, validJson());
        byte[] body = first.getBody();
        ChatMessage parsed = messageReader.read(body, "application/json");
        List<ChatMessage> expected = List.of(parsed, parsed);
        List<Message> deliveries = List.of(first, second);

        consumer.receive(deliveries, channel);

        InOrder order = inOrder(batchWriteService, channel);
        order.verify(batchWriteService).saveBatch(expected);
        order.verify(channel).basicAck(11, false);
        order.verify(channel).basicAck(12, false);
        verifyNoMoreInteractions(channel);
    }

    /** Eine ungültige Nachricht darf gültige Nachbarn nicht blockieren. */
    @Test
    void rejectsOnlyInvalidDelivery() throws IOException {
        Message invalid = message(21, "{}");
        Message valid = message(22, validJson());
        List<Message> deliveries = List.of(invalid, valid);

        consumer.receive(deliveries, channel);

        InOrder order = inOrder(channel, batchWriteService);
        order.verify(channel).basicNack(21, false, false);
        order.verify(batchWriteService).saveBatch(anyList());
        order.verify(channel).basicAck(22, false);
        verifyNoMoreInteractions(channel);
    }

    /** Ohne gültige Daten ist auch kein Aufruf des Schreibservices notwendig. */
    @Test
    void rejectsInvalidBatchWithoutWriting() throws IOException {
        Message invalid = message(31, "not JSON");
        List<Message> deliveries = List.of(invalid);

        consumer.receive(deliveries, channel);

        verify(channel).basicNack(31, false, false);
        verifyNoInteractions(batchWriteService);
        verifyNoMoreInteractions(channel);
    }

    /** Ein Speicherfehler darf niemals als erfolgreich verarbeitete Nachricht bestätigt werden. */
    @Test
    void doesNotAcknowledgeFailedWrite() throws IOException {
        Message valid = message(41, validJson());
        List<Message> deliveries = List.of(valid);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Database unavailable");
        doThrow(failure).when(batchWriteService).saveBatch(anyList());

        consumer.receive(deliveries, channel);

        verify(channel).basicNack(41, false, true);
        verifyNoMoreInteractions(channel);
    }

    /** Auch ein unklarer COMMIT-Ausgang muss erneut zugestellt werden können. */
    @Test
    void requeuesValidNeighboursOnCommitFailure() throws IOException {
        Message first = message(61, validJson());
        Message invalid = message(62, "{}");
        Message second = message(63, validJson());
        List<Message> deliveries = List.of(first, invalid, second);
        TransactionSystemException failure = new TransactionSystemException("Commit failed");
        doThrow(failure).when(batchWriteService).saveBatch(anyList());

        consumer.receive(deliveries, channel);

        verify(channel).basicNack(62, false, false);
        verify(channel).basicNack(61, false, true);
        verify(channel).basicNack(63, false, true);
        verifyNoMoreInteractions(channel);
    }

    /** Beim Beenden bleiben die Nachrichten unbestätigt; das Interrupt-Signal bleibt erhalten. */
    @Test
    void preservesInterruptWithoutAcknowledging() {
        Message valid = message(71, validJson());
        List<Message> deliveries = List.of(valid);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Database unavailable");
        doThrow(failure).when(batchWriteService).saveBatch(anyList());
        Thread currentThread = Thread.currentThread();
        currentThread.interrupt();
        try {
            assertThrows(IOException.class, () -> consumer.receive(deliveries, channel));
            assertTrue(currentThread.isInterrupted());
            verifyNoInteractions(channel);
        } finally {
            // Nur der Test entfernt das Signal, damit weitere Tests normal laufen.
            Thread.interrupted();
        }
    }

    /** Nach einem fehlgeschlagenen NACK werden alte Zustellnummern nicht weiterverwendet. */
    @Test
    void propagatesRequeueChannelFailure() throws IOException {
        Message first = message(81, validJson());
        Message second = message(82, validJson());
        List<Message> deliveries = List.of(first, second);
        DataAccessResourceFailureException failure = new DataAccessResourceFailureException("Database unavailable");
        doThrow(failure).when(batchWriteService).saveBatch(anyList());
        doThrow(new IOException("Channel closed")).when(channel).basicNack(81, false, true);

        assertThrows(IOException.class, () -> consumer.receive(deliveries, channel));

        verify(channel).basicNack(81, false, true);
        verifyNoMoreInteractions(channel);
    }

    /** Alte Zustellnummern dürfen nach einem Kanalfehler nicht weiter bestätigt werden. */
    @Test
    void propagatesChannelFailure() throws IOException {
        Message first = message(51, validJson());
        Message second = message(52, validJson());
        List<Message> deliveries = List.of(first, second);
        doThrow(new IOException("Channel closed")).when(channel).basicAck(51, false);

        assertThrows(IOException.class, () -> consumer.receive(deliveries, channel));

        verify(channel).basicAck(51, false);
        verifyNoMoreInteractions(channel);
    }

    /** Zustellnummer und Content-Type entsprechen den vom Broker gelieferten Eigenschaften. */
    private Message message(long deliveryTag, String json) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        properties.setDeliveryTag(deliveryTag);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        return new Message(body, properties);
    }

    /** Der Vertrag enthält keine Java-Klassennamen. */
    private String validJson() {
        return """
                {"id":"11111111-1111-4111-8111-111111111111",
                 "roomId":"22222222-2222-4222-8222-222222222222",
                 "senderId":"anna","senderName":"Anna Muster",
                 "content":"Hallo","sentAt":"2026-09-29T08:00:00Z"}
                """;
    }
}
