package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.RecentMessageBuffer;
import ch.benedict.m321.chatservice.service.StreamService;
import ch.benedict.m321.chatservice.support.SseClient;
import ch.benedict.m321.chatservice.support.TestChat;
import ch.benedict.m321.chatservice.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Prüft S7 und die Übergabe-Rennen mit echtem HTTP, RabbitMQ und PostgreSQL. */
@Testcontainers
class StreamReconnectIntegrationTest {

    @Container
    static PostgreSQLContainer<?> database = TestDatabase.create();

    @Container
    static RabbitMQContainer broker = new RabbitMQContainer("rabbitmq:3.13-management");

    /** S7 wechselt sogar die Instanz: genau 50 fehlende Nachrichten, dann wieder live. */
    @Test
    void replaysExactlyFiftyAfterTenSecondsAndContinuesLiveOnOtherInstance() throws Exception {
        String[] settings = TestDatabase.arguments(database);
        UUID room = UUID.randomUUID();
        try (TestChat first = new TestChat(broker, settings);
             TestChat second = new TestChat(broker, settings)) {
            ChatMessage cursor;
            try (SseClient initial = new SseClient(first, room, null)) {
                cursor = first.send(room, "sender", "cursor");
                TestDatabase.save(first, cursor);
                assertEquals(cursor, initial.take(1, Duration.ofSeconds(5)).getFirst());
            }
            List<ChatMessage> expected = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                ChatMessage message = first.send(room, "sender", "Missing " + index);
                TestDatabase.save(first, message);
                expected.add(message);
            }
            // Die zehn Sekunden gehören zum ausdrücklich vorgegebenen Szenario.
            Thread.sleep(10_000);
            try (SseClient resumed = new SseClient(second, room, cursor.id().toString())) {
                assertEquals(expected, resumed.take(50, Duration.ofSeconds(5)));
                resumed.assertQuiet(Duration.ofMillis(200));
                ChatMessage next = first.send(room, "sender", "live again");
                assertEquals(next, resumed.take(1, Duration.ofSeconds(5)).getFirst());
                resumed.assertQuiet(Duration.ofMillis(200));
            }
        }
    }

    /** Ein verfügbarer DB-Lesepfad darf einen noch nicht gespeicherten RAM-Cursor ergänzen. */
    @Test
    void includesCursorAndMessagesNotYetStoredByWriter() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = new TestChat(broker, TestDatabase.arguments(database))) {
            ChatMessage cursor = application.send(room, "sender", "not stored cursor");
            ChatMessage missing = application.send(room, "sender", "not stored message");
            RecentMessageBuffer recent = application.context.getBean(RecentMessageBuffer.class);
            await().atMost(Duration.ofSeconds(5)).until(() -> recent.find(missing.id()) != null);
            try (SseClient resumed = new SseClient(application, room, cursor.id().toString())) {
                assertEquals(List.of(missing), resumed.take(1, Duration.ofSeconds(5)));
                resumed.assertQuiet(Duration.ofMillis(200));
            }
        }
    }

    /** Eine echte Tabellensperre hält SQL an, während der Live-Pfad weiterhin neue Ereignisse übernimmt. */
    @Test
    void buffersNewEventsWhileHistoryIsLoadingIncludingOlderForeignTimestamp() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = new TestChat(broker, TestDatabase.arguments(database));
             var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatMessage cursor = storedMessage(room, Instant.parse("2026-11-13T10:00:00Z"), "cursor");
            TestDatabase.save(application, cursor);
            DataSource source = application.context.getBean(DataSource.class);
            StreamService streams = application.context.getBean(StreamService.class);
            try (Connection lock = source.getConnection()) {
                lock.setAutoCommit(false);
                lockTable(lock);
                var future = requests.submit(() -> new SseClient(application, room, cursor.id().toString()));
                List<ChatMessage> expected;
                try {
                    await().atMost(Duration.ofSeconds(3)).until(() -> streams.connectionCount() == 1);
                    assertFalse(future.isDone(), "The DB query must still be blocked");
                    ChatMessage first = application.send(room, "sender", "arrived during SQL");
                    ChatMessage second = application.send(room, "sender", "arrived afterwards");
                    Instant oldTime = cursor.sentAt().minusSeconds(1);
                    ChatMessage delayed = new ChatMessage(UUID.randomUUID(), room, "late-sender",
                            "Late Sender", "late event with older time", oldTime);
                    RabbitTemplate publisher = application.context.getBean(RabbitTemplate.class);
                    publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", delayed);
                    RecentMessageBuffer recent = application.context.getBean(RecentMessageBuffer.class);
                    await().atMost(Duration.ofSeconds(3)).until(() -> recent.find(delayed.id()) != null);
                    expected = List.of(delayed, first, second);
                } finally {
                    lock.rollback();
                }
                try (SseClient resumed = future.get(10, TimeUnit.SECONDS)) {
                    assertEquals(expected, resumed.take(3, Duration.ofSeconds(5)));
                    resumed.assertQuiet(Duration.ofMillis(200));
                    ChatMessage next = application.send(room, "sender", "live after transition");
                    assertEquals(next, resumed.take(1, Duration.ofSeconds(5)).getFirst());
                }
            }
        }
    }

    /** Eine Überschreitung wird vor dem ersten SSE-Header gemeldet und räumt die Sitzung auf. */
    @Test
    void rejectsReplayBeyondConfiguredLimit() throws Exception {
        UUID room = UUID.randomUUID();
        String[] settings = settings("--live.replay-limit=2");
        try (TestChat application = new TestChat(broker, settings)) {
            Instant time = Instant.parse("2026-11-13T10:00:00Z");
            ChatMessage cursor = storedMessage(room, time, "cursor");
            TestDatabase.save(application, cursor);
            for (int index = 1; index <= 3; index++) {
                ChatMessage message = storedMessage(room, time.plusSeconds(index), "missing");
                TestDatabase.save(application, message);
            }
            assertEquals(409, application.get("/stream?roomId=" + room, cursor.id().toString()).statusCode());
            StreamService streams = application.context.getBean(StreamService.class);
            assertEquals(0, streams.connectionCount());
        }
    }

    /** Der Writer kann vor dem Live-Consumer fertig sein; dessen spätere Kopie darf nicht doppeln. */
    @Test
    void ignoresBrokerCopyAlreadyReadFromDatabase() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = new TestChat(broker, TestDatabase.arguments(database))) {
            Instant time = Instant.parse("2026-11-13T10:00:00Z");
            ChatMessage cursor = storedMessage(room, time, "cursor");
            ChatMessage missing = storedMessage(room, time.plusSeconds(1), "already stored");
            TestDatabase.save(application, cursor);
            TestDatabase.save(application, missing);
            try (SseClient resumed = new SseClient(application, room, cursor.id().toString())) {
                assertEquals(missing, resumed.take(1, Duration.ofSeconds(5)).getFirst());
                RabbitTemplate publisher = application.context.getBean(RabbitTemplate.class);
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", missing);
                resumed.assertQuiet(Duration.ofMillis(300));
                ChatMessage next = application.send(room, "sender", "new after duplicate");
                assertEquals(next, resumed.take(1, Duration.ofSeconds(5)).getFirst());
            }
        }
    }

    /** Fehlerhafte, unbekannte und raumfremde Cursor dürfen keine fremde Historie offenlegen. */
    @Test
    void rejectsInvalidUnknownAndForeignCursors() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = new TestChat(broker, TestDatabase.arguments(database))) {
            assertEquals(400, application.get("/stream?roomId=" + room, "bad-id").statusCode());
            assertEquals(404, application.get("/stream?roomId=" + room, UUID.randomUUID().toString()).statusCode());
            ChatMessage foreign = storedMessage(UUID.randomUUID(), Instant.now(), "foreign");
            TestDatabase.save(application, foreign);
            assertEquals(404, application.get("/stream?roomId=" + room, foreign.id().toString()).statusCode());
        }
    }

    /** Ein DB-Ausfall betrifft Wiederaufnahme, aber weder frische Live-Verbindung noch POST. */
    @Test
    void returns503ForReplayWhileLiveWorksWithoutDatabase() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = new TestChat(broker, TestDatabase.arguments(database))) {
            ChatMessage cursor = storedMessage(room, Instant.now(), "cursor");
            TestDatabase.save(application, cursor);
            String containerId = database.getContainerId();
            var docker = DockerClientFactory.instance().client();
            docker.stopContainerCmd(containerId).withTimeout(1).exec();
            try {
                assertEquals(503, application.get("/stream?roomId=" + room, cursor.id().toString()).statusCode());
                try (SseClient live = new SseClient(application, room, null)) {
                    ChatMessage message = application.send(room, "sender", "live without DB");
                    assertEquals(message, live.take(1, Duration.ofSeconds(5)).getFirst());
                }
            } finally {
                docker.startContainerCmd(containerId).exec();
                await().atMost(Duration.ofSeconds(15)).ignoreExceptions().until(
                        () -> database.execInContainer("pg_isready", "-U", "chat", "-d", "chat").getExitCode() == 0);
            }
        }
    }

    /** Gleiche gespeicherte Zeiten behalten den in SQL festgelegten UUID-Gleichstand. */
    @Test
    void replaysEqualTimesUsingCanonicalUuidOrder() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = new TestChat(broker, TestDatabase.arguments(database))) {
            Instant time = Instant.parse("2026-11-13T10:00:00Z");
            ChatMessage cursor = new ChatMessage(UUID.fromString("00000000-0000-0000-0000-000000000001"), room,
                    "sender", "Sender", "cursor", time);
            ChatMessage first = new ChatMessage(UUID.fromString("80000000-0000-0000-0000-000000000001"), room,
                    "sender", "Sender", "first", time);
            ChatMessage second = new ChatMessage(UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"), room,
                    "sender", "Sender", "second", time);
            TestDatabase.save(application, second);
            TestDatabase.save(application, cursor);
            TestDatabase.save(application, first);
            try (SseClient resumed = new SseClient(application, room, cursor.id().toString())) {
                assertEquals(List.of(first, second), resumed.take(2, Duration.ofSeconds(5)));
            }
        }
    }

    /** Der Puffer darf während einer blockierten Historienabfrage nicht still Nachrichten verlieren. */
    @Test
    void rejectsOverflowDuringHistoryLoad() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = new TestChat(broker, settings("--live.client-buffer-size=1"));
             var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            ChatMessage cursor = storedMessage(room, Instant.now(), "cursor");
            TestDatabase.save(application, cursor);
            DataSource source = application.context.getBean(DataSource.class);
            StreamService streams = application.context.getBean(StreamService.class);
            try (Connection lock = source.getConnection()) {
                lock.setAutoCommit(false);
                lockTable(lock);
                var future = requests.submit(() -> application.get("/stream?roomId=" + room, cursor.id().toString()));
                try {
                    await().atMost(Duration.ofSeconds(3)).until(() -> streams.connectionCount() == 1);
                    application.send(room, "sender", "first");
                    ChatMessage second = application.send(room, "sender", "overflow");
                    RecentMessageBuffer recent = application.context.getBean(RecentMessageBuffer.class);
                    await().atMost(Duration.ofSeconds(3)).until(() -> recent.find(second.id()) != null);
                } finally {
                    lock.rollback();
                }
                assertEquals(409, future.get(10, TimeUnit.SECONDS).statusCode());
                assertEquals(0, streams.connectionCount());
            }
        }
    }

    /** Die Sperre dient nur dem Test und hält echte SELECTs reproduzierbar an. */
    private void lockTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("LOCK TABLE message IN ACCESS EXCLUSIVE MODE");
        }
    }

    /** Erzeugt unabhängige IDs, damit kein Test die Daten eines anderen zurücksetzen muss. */
    private ChatMessage storedMessage(UUID room, Instant time, String content) {
        return new ChatMessage(UUID.randomUUID(), room, "sender", "Sender", content, time);
    }

    /** Zusätzliche Grenzwerte überschreiben nur den jeweiligen Test-Kontext. */
    private String[] settings(String extra) {
        List<String> result = new ArrayList<>();
        for (String argument : TestDatabase.arguments(database)) {
            result.add(argument);
        }
        result.add(extra);
        return result.toArray(String[]::new);
    }
}
