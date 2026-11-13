package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.support.SseClient;
import ch.benedict.m321.chatservice.support.TestChat;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Stellt S3 mit zwei HTTP-Anwendungen an einem echten Broker vollständig nach. */
@Testcontainers
class LiveDistributionIntegrationTest {

    // Gleiche Testfristen werden gemeinsam benannt statt pro Aufruf wiederholt.
    private static final Duration SECONDS_10 = Duration.ofSeconds(10);
    private static final Duration MILLISECONDS_200 = Duration.ofMillis(200);


    @Container
    static RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:3.13-management");

    /** Jeder Raum-A-Client muss beide Sender vollständig und je Sender geordnet erhalten. */
    @Test
    void deliversBothSendersToBothInstancesWithoutRoomLeaks() throws Exception {
        UUID room = UUID.randomUUID();
        UUID otherRoom = UUID.randomUUID();
        try (TestChat first = new TestChat(broker);
             TestChat second = new TestChat(broker);
             SseClient firstClient = new SseClient(first, room, null);
             SseClient secondClient = new SseClient(second, room, null);
             SseClient other = new SseClient(first, otherRoom, null);
             var senders = Executors.newFixedThreadPool(2)) {
            var firstResult = senders.submit(() -> sendSeries(first, room, "sender-a"));
            var secondResult = senders.submit(() -> sendSeries(second, room, "sender-b"));
            var response = firstResult.get(30, TimeUnit.SECONDS);
            List<ChatMessage> expected = new ArrayList<>(response);
            var response2 = secondResult.get(30, TimeUnit.SECONDS);
            expected.addAll(response2);

            var receivedMessages = firstClient.take(400, SECONDS_10);
            assertComplete(receivedMessages, expected);
            var receivedMessages2 = secondClient.take(400, SECONDS_10);
            assertComplete(receivedMessages2, expected);
            firstClient.assertQuiet(MILLISECONDS_200);
            secondClient.assertQuiet(MILLISECONDS_200);
            other.assertQuiet(MILLISECONDS_200);
        }
    }

    /** Ein Absender sendet nacheinander, während der zweite Absender parallel arbeitet. */
    private List<ChatMessage> sendSeries(TestChat application, UUID room, String sender) throws Exception {
        List<ChatMessage> result = new ArrayList<>();
        for (int index = 0; index < 200; index++) {
            ChatMessage message = application.send(room, sender, "Message " + index);
            result.add(message);
        }
        return result;
    }

    /** Nur Zählen würde doppelte und fehlende IDs gegeneinander aufheben. */
    private void assertComplete(List<ChatMessage> received, List<ChatMessage> expected) {
        Map<UUID, ChatMessage> byId = new HashMap<>();
        for (ChatMessage message : expected) {
            var messageId = message.id();
            byId.put(messageId, message);
        }
        var count = byId.size();
        assertEquals(400, count);
        Set<UUID> seen = new HashSet<>();
        for (ChatMessage message : received) {
            var messageId2 = message.id();
            var addResult = seen.add(messageId2);
            assertTrue(addResult, "Duplicate delivery");
            var messageId3 = message.id();
            var response = byId.get(messageId3);
            assertEquals(response, message);
        }
        var keySetResult = byId.keySet();
        assertEquals(keySetResult, seen);
        for (String sender : List.of("sender-a", "sender-b")) {
            List<UUID> expectedOrder = senderIds(expected, sender);
            List<UUID> actualOrder = senderIds(received, sender);
            assertEquals(expectedOrder, actualOrder, "Sender order changed");
        }
    }

    /** Zwischen verschiedenen Absendern wird keine künstliche globale Reihenfolge verlangt. */
    private List<UUID> senderIds(List<ChatMessage> messages, String sender) {
        List<UUID> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            String senderId = message.senderId();
            if (sender.equals(senderId)) {
                var messageId = message.id();
                result.add(messageId);
            }
        }
        return result;
    }
}
