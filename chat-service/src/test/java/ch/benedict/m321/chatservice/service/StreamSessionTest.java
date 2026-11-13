package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Prüft Reihenfolge und Rückstau isoliert, ohne langsame Netzwerkbedingungen erraten zu müssen. */
class StreamSessionTest {

    /** Ein Worker sendet vorgefüllte Ereignisse in derselben Reihenfolge. */
    @Test
    void keepsFifoOrder() throws Exception {
        RecordingEmitter emitter = new RecordingEmitter();
        AtomicInteger closed = new AtomicInteger();
        StreamSession session = new StreamSession(UUID.randomUUID(), 2, emitter, ignored -> closed.incrementAndGet());
        ChatMessage first = message("first");
        ChatMessage second = message("second");
        assertTrue(session.offer(first));
        assertTrue(session.offer(second));
        session.start();
        assertEquals(first, emitter.messages.poll(5, TimeUnit.SECONDS));
        assertEquals(second, emitter.messages.poll(5, TimeUnit.SECONDS));
        session.close();
        session.close();
        assertEquals(1, closed.get());
    }

    /** Ein blockierter Socket darf weder Puffergrenze noch einen anderen Worker aufhalten. */
    @Test
    void isolatesBlockedWriterAndRejectsOverflow() throws Exception {
        BlockingEmitter slowEmitter = new BlockingEmitter();
        StreamSession slow = new StreamSession(UUID.randomUUID(), 1, slowEmitter, ignored -> { });
        RecordingEmitter healthyEmitter = new RecordingEmitter();
        StreamSession healthy = new StreamSession(UUID.randomUUID(), 2, healthyEmitter, ignored -> { });
        try {
            slow.start();
            assertTrue(slowEmitter.entered.await(5, TimeUnit.SECONDS));
            ChatMessage message = message("healthy");
            assertTrue(slow.offer(message));
            // Eine bereits bekannte ID belegt keinen zweiten Platz im Puffer.
            assertTrue(slow.offer(message));
            ChatMessage nextMessage = message("overflow");
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> assertFalse(slow.offer(nextMessage)));
            healthy.start();
            assertTrue(healthy.offer(message));
            assertEquals(message, healthyEmitter.messages.poll(5, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(500), slow::close);
        } finally {
            slowEmitter.release.countDown();
            slow.close();
            healthy.close();
        }
    }

    /** Einfache unveränderliche Nutzdaten halten die Tests unabhängig vom HTTP-Publisher. */
    private ChatMessage message(String content) {
        return new ChatMessage(UUID.randomUUID(), UUID.randomUUID(), "sender", "Sender", content, Instant.now());
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
                Thread.currentThread().interrupt();
                throw new IOException("Writer interrupted", exception);
            }
        }
    }
}
