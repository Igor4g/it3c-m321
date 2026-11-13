package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.RecentMessageBuffer;
import ch.benedict.m321.chatservice.service.StreamService;
import ch.benedict.m321.chatservice.support.SseClient;
import ch.benedict.m321.chatservice.support.TestChat;
import ch.benedict.m321.chatservice.support.TestDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

    // Gleiche Testfristen werden gemeinsam benannt statt pro Aufruf wiederholt.
    private static final Duration SECONDS_5 = Duration.ofSeconds(5);
    private static final Duration MILLISECONDS_200 = Duration.ofMillis(200);
    private static final Duration SECONDS_3 = Duration.ofSeconds(3);
    private static final Duration MILLISECONDS_300 = Duration.ofMillis(300);


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
                var receivedMessages = initial.take(1, SECONDS_5);
                var receivedMessage = receivedMessages.getFirst();
                assertEquals(cursor, receivedMessage);
            }
            List<ChatMessage> expected = new ArrayList<>();
            for (int index = 0; index < 50; index++) {
                ChatMessage message = first.send(room, "sender", "Missing " + index);
                TestDatabase.save(first, message);
                expected.add(message);
            }
            // Die zehn Sekunden gehören zum ausdrücklich vorgegebenen Szenario.
            Thread.sleep(10_000);
            try (SseClient resumed = reconnect(second, room, cursor)) {
                var receivedMessages2 = resumed.take(50, SECONDS_5);
                assertEquals(expected, receivedMessages2);
                resumed.assertQuiet(MILLISECONDS_200);
                ChatMessage next = first.send(room, "sender", "live again");
                var receivedMessages3 = resumed.take(1, SECONDS_5);
                var receivedMessage2 = receivedMessages3.getFirst();
                assertEquals(next, receivedMessage2);
                resumed.assertQuiet(MILLISECONDS_200);
            }
        }
    }

    /** Ein verfügbarer DB-Lesepfad darf einen noch nicht gespeicherten RAM-Cursor ergänzen. */
    @Test
    void includesCursorAndMessagesNotYetStoredByWriter() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = startApplication()) {
            ChatMessage cursor = application.send(room, "sender", "not stored cursor");
            ChatMessage missing = application.send(room, "sender", "not stored message");
            RecentMessageBuffer recent = application.context.getBean(RecentMessageBuffer.class);
            var readiness = await();
            var boundedWait = readiness.atMost(SECONDS_5);
            UUID missingId = missing.id();
            boundedWait.until(() -> recent.find(missingId) != null);
            try (SseClient resumed = reconnect(application, room, cursor)) {
                var expectedValues = List.of(missing);
                var receivedMessages = resumed.take(1, SECONDS_5);
                assertEquals(expectedValues, receivedMessages);
                resumed.assertQuiet(MILLISECONDS_200);
            }
        }
    }

    /** Beobachtete RAM-Positionen haben Vorrang vor gegenteiliger SQL-Zeit-/UUID-Sortierung. */
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void replaysPersistedObservedSuccessorDespiteOlderSqlPosition(boolean equalTime) throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = startApplication()) {
            RecentMessageBuffer recent = application.context.getBean(RecentMessageBuffer.class);
            RabbitTemplate publisher = application.context.getBean(RabbitTemplate.class);
            Instant now = Instant.now();
            Instant futureTime = now.plusSeconds(3600);
            ChatMessage previous = storedMessage(room, futureTime, "previous despite newer clock");
            publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", previous);
            awaitMessage(recent, previous);
            TestDatabase.save(application, previous);
            ChatMessage cursor = application.send(room, "cursor-sender", "received cursor");
            awaitMessage(recent, cursor);
            TestDatabase.save(application, cursor);
            Instant cursorTime = cursor.sentAt();
            Instant missingTime = cursorTime.minusSeconds(1);
            UUID missingId = UUID.fromString("00000000-0000-0000-0000-000000000002");
            if (equalTime) {
                missingTime = cursorTime;
                missingId = UUID.fromString("00000000-0000-0000-0000-000000000003");
            }
            ChatMessage missing = new ChatMessage(missingId, room, "delayed-sender",
                    "Delayed Sender", "observed after cursor", missingTime);
            publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", missing);
            awaitMessage(recent, missing);
            TestDatabase.save(application, missing);
            List<ChatMessage> expectedArrival = List.of(previous, cursor, missing);
            List<ChatMessage> actualArrival = recent.snapshot();
            assertEquals(expectedArrival, actualArrival);
            try (SseClient resumed = reconnect(application, room, cursor)) {
                ChatMessage fresh = application.send(room, "fresh-sender", "live marker");
                List<ChatMessage> expectedMissing = List.of(missing);
                List<ChatMessage> replayed = resumed.take(1, SECONDS_5);
                assertEquals(expectedMissing, replayed);
                List<ChatMessage> expectedFresh = List.of(fresh);
                List<ChatMessage> live = resumed.take(1, SECONDS_5);
                assertEquals(expectedFresh, live);
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", previous);
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", cursor);
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", missing);
                resumed.assertQuiet(MILLISECONDS_300);
            }
        }
    }

    /** Wartet auf den tatsächlichen Consumer-Empfang, nicht auf eine angenommene Netzwerklaufzeit. */
    private void awaitMessage(RecentMessageBuffer recent, ChatMessage message) {
        UUID messageId = message.id();
        var readiness = await();
        var boundedWait = readiness.atMost(SECONDS_5);
        boundedWait.until(() -> recent.find(messageId) != null);
    }

    /** Eine echte Tabellensperre hält SQL an, während der Live-Pfad weiterhin neue Ereignisse übernimmt. */
    @Test
    void buffersNewEventsWhileHistoryIsLoadingIncludingOlderForeignTimestamp() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = startApplication();
             var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var timestamp = Instant.parse("2026-11-13T10:00:00Z");
            ChatMessage cursor = storedMessage(room, timestamp, "cursor");
            var sentAtResult = cursor.sentAt();
            Instant historyTime = sentAtResult.plusSeconds(1);
            ChatMessage historical = storedMessage(room, historyTime, "missing before loading");
            TestDatabase.save(application, cursor);
            TestDatabase.save(application, historical);
            DataSource source = application.context.getBean(DataSource.class);
            StreamService streams = application.context.getBean(StreamService.class);
            try (Connection lock = source.getConnection()) {
                lock.setAutoCommit(false);
                lockTable(lock);
                var future = requests.submit(() -> reconnect(application, room, cursor));
                List<ChatMessage> expected;
                java.util.concurrent.Future<?> write = null;
                try {
                    var readiness = await();
                    var boundedWait = readiness.atMost(SECONDS_3);
                    boundedWait.until(() -> streams.connectionCount() == 1);
                    var isDoneResult = future.isDone();
                    assertFalse(isDoneResult, "The DB query must still be blocked");
                    ChatMessage first = application.send(room, "sender", "arrived during SQL");
                    ChatMessage second = application.send(room, "sender", "arrived afterwards");
                    var sentAtResult2 = cursor.sentAt();
                    Instant oldTime = sentAtResult2.minusSeconds(1);
                    var messageId = UUID.randomUUID();
                    ChatMessage delayed = new ChatMessage(messageId, room, "late-sender",
                            "Late Sender", "late event with older time", oldTime);
                    RabbitTemplate publisher = application.context.getBean(RabbitTemplate.class);
                    publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", delayed);
                    RecentMessageBuffer recent = application.context.getBean(RecentMessageBuffer.class);
                    var readiness2 = await();
                    var boundedWait2 = readiness2.atMost(SECONDS_3);
                    UUID delayedId = delayed.id();
                    boundedWait2.until(() -> recent.find(delayedId) != null);
                    // Der neue alte Zeitstempel wird zusätzlich gespeichert, sobald die Sperre frei ist.
                    write = requests.submit(() -> TestDatabase.save(application, delayed));
                    expected = List.of(historical, first, second, delayed);
                } finally {
                    lock.rollback();
                }
                try (SseClient resumed = future.get(10, TimeUnit.SECONDS)) {
                    write.get(10, TimeUnit.SECONDS);
                    var receivedMessages = resumed.take(4, SECONDS_5);
                    assertEquals(expected, receivedMessages);
                    resumed.assertQuiet(MILLISECONDS_200);
                    ChatMessage next = application.send(room, "sender", "live after transition");
                    var receivedMessages2 = resumed.take(1, SECONDS_5);
                    var receivedMessage = receivedMessages2.getFirst();
                    assertEquals(next, receivedMessage);
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
                var plusSecondsResult = time.plusSeconds(index);
                ChatMessage message = storedMessage(room, plusSecondsResult, "missing");
                TestDatabase.save(application, message);
            }
            var messageId = cursor.id();
            var text = messageId.toString();
            var response = application.get("/stream?roomId=" + room, text);
            var statusCode2 = response.statusCode();
            assertEquals(409, statusCode2);
            StreamService streams = application.context.getBean(StreamService.class);
            var connectionCount2 = streams.connectionCount();
            assertEquals(0, connectionCount2);
        }
    }

    /** Der Writer kann vor dem Live-Consumer fertig sein; dessen spätere Kopie darf nicht doppeln. */
    @Test
    void ignoresBrokerCopyAlreadyReadFromDatabase() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = startApplication()) {
            Instant time = Instant.parse("2026-11-13T10:00:00Z");
            ChatMessage cursor = storedMessage(room, time, "cursor");
            var plusSecondsResult = time.plusSeconds(1);
            ChatMessage missing = storedMessage(room, plusSecondsResult, "already stored");
            TestDatabase.save(application, cursor);
            TestDatabase.save(application, missing);
            try (SseClient resumed = reconnect(application, room, cursor)) {
                var receivedMessages = resumed.take(1, SECONDS_5);
                var receivedMessage = receivedMessages.getFirst();
                assertEquals(missing, receivedMessage);
                RabbitTemplate publisher = application.context.getBean(RabbitTemplate.class);
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", missing);
                resumed.assertQuiet(MILLISECONDS_300);
                ChatMessage next = application.send(room, "sender", "new after duplicate");
                var receivedMessages2 = resumed.take(1, SECONDS_5);
                var receivedMessage2 = receivedMessages2.getFirst();
                assertEquals(next, receivedMessage2);
            }
        }
    }

    /** Ein kalter Live-Puffer darf bekannte SQL-Vorgänger und den Cursor nach der Übergabe nicht erneut zeigen. */
    @Test
    void ignoresDelayedCursorAndStoredPredecessorButDeliversNewOlderTimestamp() throws Exception {
        UUID room = UUID.randomUUID();
        String[] arguments = TestDatabase.arguments(database);
        try (TestChat application = new TestChat(broker, arguments)) {
            Instant time = Instant.parse("2026-11-13T10:00:00Z");
            ChatMessage previous = storedMessage(room, time, "already received predecessor");
            Instant cursorTime = time.plusSeconds(1);
            ChatMessage cursor = storedMessage(room, cursorTime, "already received cursor");
            Instant missingTime = time.plusSeconds(2);
            ChatMessage missing = storedMessage(room, missingTime, "missing history");
            TestDatabase.save(application, previous);
            TestDatabase.save(application, cursor);
            TestDatabase.save(application, missing);
            UUID cursorId = cursor.id();
            String lastId = cursorId.toString();
            try (SseClient resumed = new SseClient(application, room, lastId)) {
                List<ChatMessage> received = resumed.take(1, SECONDS_5);
                var expectedValues = List.of(missing);
                assertEquals(expectedValues, received);
                RabbitTemplate publisher = application.context.getBean(RabbitTemplate.class);
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", missing);
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", previous);
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", cursor);
                resumed.assertQuiet(MILLISECONDS_300);
                Instant olderTime = time.minusSeconds(1);
                ChatMessage genuinelyNew = storedMessage(room, olderTime, "new id despite older clock");
                publisher.convertAndSend(QueueNames.DELIVERY_EXCHANGE, "", genuinelyNew);
                List<ChatMessage> newMessages = resumed.take(1, SECONDS_5);
                var expectedValues2 = List.of(genuinelyNew);
                assertEquals(expectedValues2, newMessages);
                resumed.assertQuiet(MILLISECONDS_200);
            }
        }
    }

    /** Fehlerhafte, unbekannte und raumfremde Cursor dürfen keine fremde Historie offenlegen. */
    @Test
    void rejectsInvalidUnknownAndForeignCursors() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = startApplication()) {
            var response = application.get("/stream?roomId=" + room, "bad-id");
            var statusCode2 = response.statusCode();
            assertEquals(400, statusCode2);
            var messageId = UUID.randomUUID();
            var text = messageId.toString();
            var response2 = application.get("/stream?roomId=" + room, text);
            var statusCode3 = response2.statusCode();
            assertEquals(404, statusCode3);
            var messageId2 = UUID.randomUUID();
            var timestamp = Instant.now();
            ChatMessage foreign = storedMessage(messageId2, timestamp, "foreign");
            TestDatabase.save(application, foreign);
            var messageId3 = foreign.id();
            var text2 = messageId3.toString();
            var response3 = application.get("/stream?roomId=" + room, text2);
            var statusCode4 = response3.statusCode();
            assertEquals(404, statusCode4);
        }
    }

    /** Ein DB-Ausfall betrifft Wiederaufnahme, aber weder frische Live-Verbindung noch POST. */
    @Test
    void returns503ForReplayWhileLiveWorksWithoutDatabase() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = startApplication()) {
            var timestamp = Instant.now();
            ChatMessage cursor = storedMessage(room, timestamp, "cursor");
            TestDatabase.save(application, cursor);
            String containerId = database.getContainerId();
            var instanceResult = DockerClientFactory.instance();
            var docker = instanceResult.client();
            var stopContainerCmdResult = docker.stopContainerCmd(containerId);
            var withTimeoutResult = stopContainerCmdResult.withTimeout(1);
            withTimeoutResult.exec();
            try {
                var messageId = cursor.id();
                var text = messageId.toString();
                var response = application.get("/stream?roomId=" + room, text);
                var statusCode2 = response.statusCode();
                assertEquals(503, statusCode2);
                try (SseClient live = new SseClient(application, room, null)) {
                    ChatMessage message = application.send(room, "sender", "live without DB");
                    var receivedMessages = live.take(1, SECONDS_5);
                    var receivedMessage = receivedMessages.getFirst();
                    assertEquals(message, receivedMessage);
                }
            } finally {
                var startContainerCmdResult = docker.startContainerCmd(containerId);
                startContainerCmdResult.exec();
                var readiness = await();
                var timeout2 = Duration.ofSeconds(15);
                var boundedWait = readiness.atMost(timeout2);
                var ignoreExceptionsResult = boundedWait.ignoreExceptions();
                ignoreExceptionsResult.until(
                        () -> {
                            var result = database.execInContainer("pg_isready", "-U", "chat", "-d", "chat");
                            return result.getExitCode() == 0;
                        });
            }
        }
    }

    /** Gleiche gespeicherte Zeiten behalten den in SQL festgelegten UUID-Gleichstand. */
    @Test
    void replaysEqualTimesUsingCanonicalUuidOrder() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = startApplication()) {
            Instant time = Instant.parse("2026-11-13T10:00:00Z");
            var messageId = UUID.fromString("00000000-0000-0000-0000-000000000001");
            ChatMessage cursor = new ChatMessage(messageId, room,
                    "sender", "Sender", "cursor", time);
            var messageId2 = UUID.fromString("80000000-0000-0000-0000-000000000001");
            ChatMessage first = new ChatMessage(messageId2, room,
                    "sender", "Sender", "first", time);
            var messageId3 = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
            ChatMessage second = new ChatMessage(messageId3, room,
                    "sender", "Sender", "second", time);
            TestDatabase.save(application, second);
            TestDatabase.save(application, cursor);
            TestDatabase.save(application, first);
            try (SseClient resumed = reconnect(application, room, cursor)) {
                var expectedValues = List.of(first, second);
                var receivedMessages = resumed.take(2, SECONDS_5);
                assertEquals(expectedValues, receivedMessages);
            }
        }
    }

    /** Der Puffer darf während einer blockierten Historienabfrage nicht still Nachrichten verlieren. */
    @Test
    void rejectsOverflowDuringHistoryLoad() throws Exception {
        UUID room = UUID.randomUUID();
        try (TestChat application = startApplication("--live.client-buffer-size=1");
             var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var timestamp = Instant.now();
            ChatMessage cursor = storedMessage(room, timestamp, "cursor");
            TestDatabase.save(application, cursor);
            DataSource source = application.context.getBean(DataSource.class);
            StreamService streams = application.context.getBean(StreamService.class);
            try (Connection lock = source.getConnection()) {
                lock.setAutoCommit(false);
                lockTable(lock);
                UUID cursorId = cursor.id();
                String lastId = cursorId.toString();
                String path = "/stream?roomId=" + room;
                var future = requests.submit(() -> application.get(path, lastId));
                try {
                    var readiness = await();
                    var boundedWait = readiness.atMost(SECONDS_3);
                    boundedWait.until(() -> streams.connectionCount() == 1);
                    application.send(room, "sender", "first");
                    ChatMessage second = application.send(room, "sender", "overflow");
                    RecentMessageBuffer recent = application.context.getBean(RecentMessageBuffer.class);
                    var readiness2 = await();
                    var boundedWait2 = readiness2.atMost(SECONDS_3);
                    UUID secondId = second.id();
                    boundedWait2.until(() -> recent.find(secondId) != null);
                } finally {
                    lock.rollback();
                }
                var response = future.get(10, TimeUnit.SECONDS);
                var statusCode2 = response.statusCode();
                assertEquals(409, statusCode2);
                var connectionCount2 = streams.connectionCount();
                assertEquals(0, connectionCount2);
            }
        }
    }

    /** Gemeinsame Datenbankparameter halten die wiederholte HTTP-Testvorbereitung kurz. */
    private TestChat startApplication(String... extraArguments) {
        List<String> arguments = new ArrayList<>();
        String[] databaseArguments = TestDatabase.arguments(database);
        for (String argument : databaseArguments) {
            arguments.add(argument);
        }
        for (String argument : extraArguments) {
            arguments.add(argument);
        }
        String[] settings = arguments.toArray(String[]::new);
        return new TestChat(broker, settings);
    }

    /** Der Helper macht die Umwandlung vom Cursorobjekt in den tatsächlichen HTTP-Header explizit. */
    private SseClient reconnect(TestChat application, UUID room, ChatMessage cursor) throws Exception {
        UUID cursorId = cursor.id();
        String lastId = cursorId.toString();
        return new SseClient(application, room, lastId);
    }

    /** Die Sperre dient nur dem Test und hält echte SELECTs reproduzierbar an. */
    private void lockTable(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("LOCK TABLE message IN ACCESS EXCLUSIVE MODE");
        }
    }

    /** Erzeugt unabhängige IDs, damit kein Test die Daten eines anderen zurücksetzen muss. */
    private ChatMessage storedMessage(UUID room, Instant time, String content) {
        var messageId = UUID.randomUUID();
        return new ChatMessage(messageId, room, "sender", "Sender", content, time);
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
