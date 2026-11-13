package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.LiveProperties;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft feste Speichergrenzen und erhält die wirkliche Empfangsreihenfolge. */
class RecentMessageBufferTest {

    /** Überlauf entfernt den ältesten Eintrag; eine wiederholte ID verändert keine Nutzdaten. */
    @Test
    void evictsOldestAndIgnoresRepeatedIds() {
        LiveProperties properties = new LiveProperties(2, 2, 2);
        RecentMessageBuffer buffer = new RecentMessageBuffer(properties);
        ChatMessage first = message("first");
        ChatMessage second = message("second");
        ChatMessage third = message("third");
        var addResult = buffer.add(first);
        assertTrue(addResult);
        var addResult2 = buffer.add(second);
        assertTrue(addResult2);
        var addResult3 = buffer.add(second);
        assertFalse(addResult3);
        buffer.add(third);
        var messageId = first.id();
        var foundMessage = buffer.find(messageId);
        assertNull(foundMessage);
        var expectedValues = List.of(second, third);
        var snapshotResult = buffer.snapshot();
        assertEquals(expectedValues, snapshotResult);
        List<ChatMessage> copy = buffer.snapshot();
        copy.clear();
        var snapshotResult2 = buffer.snapshot();
        var count = snapshotResult2.size();
        assertEquals(2, count);
    }

    /** Ein fremder Zeitstempel darf die beobachtete Broker-Reihenfolge im Puffer nicht verändern. */
    @Test
    void keepsArrivalOrderInsteadOfSortingByTime() {
        var liveProperties = new LiveProperties(2, 2, 2);
        RecentMessageBuffer buffer = new RecentMessageBuffer(liveProperties);
        ChatMessage later = message("later");
        var messageId = UUID.randomUUID();
        var roomIdResult = later.roomId();
        ChatMessage earlier = new ChatMessage(messageId, roomIdResult, "other", "Other", "earlier", Instant.EPOCH);
        buffer.add(later);
        buffer.add(earlier);
        var expectedValues = List.of(later, earlier);
        var snapshotResult = buffer.snapshot();
        assertEquals(expectedValues, snapshotResult);
    }

    /** Unterschiedliche IDs stellen unterschiedliche Ereignisse dar. */
    private ChatMessage message(String content) {
        var messageId = UUID.randomUUID();
        var messageId2 = UUID.randomUUID();
        var timestamp = Instant.now();
        return new ChatMessage(messageId, messageId2, "sender", "Sender", content, timestamp);
    }
}
