package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft Reihenfolge und Rückstau isoliert, ohne langsame Netzwerkbedingungen erraten zu müssen. */
class StreamSessionTest {

    // Gleiche Testfristen werden gemeinsam benannt statt pro Aufruf wiederholt.
    private static final Duration MILLISECONDS_500 = Duration.ofMillis(500);


    /** Ein Worker sendet vorgefüllte Ereignisse in derselben Reihenfolge. */
    @Test
    void keepsFifoOrder() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        AtomicInteger closed = new AtomicInteger();
        var messageId = UUID.randomUUID();
        StreamSession session = new StreamSession(messageId, 2, emitter, ignored -> closed.incrementAndGet());
        ChatMessage first = message("first");
        ChatMessage second = message("second");
        var accepted = session.offer(first);
        assertTrue(accepted);
        var accepted2 = session.offer(second);
        assertTrue(accepted2);
        session.start();
        var receivedMessage = emitter.messages.poll(5, TimeUnit.SECONDS);
        assertEquals(first, receivedMessage);
        var receivedMessage2 = emitter.messages.poll(5, TimeUnit.SECONDS);
        assertEquals(second, receivedMessage2);
        session.close();
        session.close();
        var response = closed.get();
        assertEquals(1, response);
    }

    /** Cursor-Ausschlüsse müssen auch bei vollem rollenden Duplikatfenster erhalten bleiben. */
    @Test
    void retainsCursorAfterLiveDeduplicationWindowRollsOver() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        UUID roomId = UUID.randomUUID();
        StreamSession session = new StreamSession(roomId, 1, emitter, ignored -> { });
        ChatMessage cursor = message("cursor");
        ChatMessage first = message("history");
        ChatMessage second = message("new live event");
        UUID cursorId = cursor.id();
        Set<UUID> excludedIds = Set.of(cursorId);
        List<ChatMessage> replay = List.of(first);
        try {
            session.prepareReplay(replay, excludedIds);
            session.start();
            ChatMessage replayed = emitter.messages.poll(5, TimeUnit.SECONDS);
            assertEquals(first, replayed);
            boolean liveAccepted = session.offer(second);
            assertTrue(liveAccepted);
            ChatMessage liveMessage = emitter.messages.poll(5, TimeUnit.SECONDS);
            assertEquals(second, liveMessage);
            boolean cursorIgnored = session.offer(cursor);
            assertTrue(cursorIgnored);
            ChatMessage extra = emitter.messages.poll(200, TimeUnit.MILLISECONDS);
            assertNull(extra);
        } finally {
            session.close();
        }
    }

    /** Ein blockierter Socket darf weder Puffergrenze noch einen anderen Worker aufhalten. */
    @Test
    void isolatesBlockedWriterAndRejectsOverflow() throws Exception {
        BlockingEmitter slowEmitter = new BlockingEmitter();
        var messageId = UUID.randomUUID();
        StreamSession slow = new StreamSession(messageId, 1, slowEmitter, ignored -> { });
        RecordingEmitter healthyEmitter = new RecordingEmitter();
        var messageId2 = UUID.randomUUID();
        StreamSession healthy = new StreamSession(messageId2, 2, healthyEmitter, ignored -> { });
        try {
            slow.start();
            var readiness = slowEmitter.entered.await(5, TimeUnit.SECONDS);
            assertTrue(readiness);
            ChatMessage message = message("healthy");
            var accepted = slow.offer(message);
            assertTrue(accepted);
            // Eine bereits bekannte ID belegt keinen zweiten Platz im Puffer.
            var accepted2 = slow.offer(message);
            assertTrue(accepted2);
            ChatMessage nextMessage = message("overflow");
            assertTimeoutPreemptively(MILLISECONDS_500, () -> {
                boolean acceptedOverflow = slow.offer(nextMessage);
                assertFalse(acceptedOverflow);
            });
            healthy.start();
            var accepted3 = healthy.offer(message);
            assertTrue(accepted3);
            var receivedMessage = healthyEmitter.messages.poll(5, TimeUnit.SECONDS);
            assertEquals(message, receivedMessage);
            assertTimeoutPreemptively(MILLISECONDS_500, slow::close);
        } finally {
            slowEmitter.release.countDown();
            slow.close();
            healthy.close();
        }
    }

    /** Einfache unveränderliche Nutzdaten halten die Tests unabhängig vom HTTP-Publisher. */
    private ChatMessage message(String content) {
        var messageId = UUID.randomUUID();
        var messageId2 = UUID.randomUUID();
        var timestamp = Instant.now();
        return new ChatMessage(messageId, messageId2, "sender", "Sender", content, timestamp);
    }

    /** Zeichnet nur Chat-Daten auf; Kommentare gehören nicht zur Nachrichtenmenge. */
    private static class RecordingEmitter extends SseEmitter {
        private final LinkedBlockingQueue<ChatMessage> messages = new LinkedBlockingQueue<>();

        /** Der Test gibt keinen Socket vor, sondern beobachtet die erzeugten Ereignisse. */
        @Override
        public void send(SseEventBuilder event) {
            for (DataWithMediaType item : event.build()) {
                if (item.getData() instanceof ChatMessage message) {
                    messages.add(message);
                }
            }
        }
    }

    /** Hält einen Schreibvorgang reproduzierbar an, bis der Test oder Abbruch ihn freigibt. */
    private static class BlockingEmitter extends SseEmitter {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        /** Der Interrupt verhält sich wie ein abgebrochener blockierter Schreibvorgang. */
        @Override
        public void send(SseEventBuilder event) throws IOException {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                var currentThreadResult = Thread.currentThread();
                currentThreadResult.interrupt();
                throw new IOException("Writer interrupted", exception);
            }
        }
    }
}
