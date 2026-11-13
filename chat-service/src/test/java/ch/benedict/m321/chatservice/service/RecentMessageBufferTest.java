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
        assertTrue(buffer.add(first));
        assertTrue(buffer.add(second));
        assertFalse(buffer.add(second));
        buffer.add(third);
        assertNull(buffer.find(first.id()));
        assertEquals(List.of(second, third), buffer.snapshot());
        List<ChatMessage> copy = buffer.snapshot();
        copy.clear();
        assertEquals(2, buffer.snapshot().size());
    }

    /** Ein fremder Zeitstempel darf die beobachtete Broker-Reihenfolge im Puffer nicht verändern. */
    @Test
    void keepsArrivalOrderInsteadOfSortingByTime() {
        RecentMessageBuffer buffer = new RecentMessageBuffer(new LiveProperties(2, 2, 2));
        ChatMessage later = message("later");
        ChatMessage earlier = new ChatMessage(UUID.randomUUID(), later.roomId(), "other", "Other", "earlier", Instant.EPOCH);
        buffer.add(later);
        buffer.add(earlier);
        assertEquals(List.of(later, earlier), buffer.snapshot());
    }

    /** Unterschiedliche IDs stellen unterschiedliche Ereignisse dar. */
    private ChatMessage message(String content) {
        return new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), "sender", "Sender", content, Instant.now());
    }
}
