package ch.benedict.m321.chatservice.support;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Liest echte SSE-Rahmen und zählt keine Kommentare oder unvollständigen Netzwerkpakete. */
public class SseClient implements AutoCloseable {

    private final HttpClient client;
    private final InputStream input;
    private final ObjectMapper mapper;
    private final LinkedBlockingQueue<ChatMessage> messages = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable> failure = new AtomicReference<>();
    private final Thread reader;
    private volatile boolean closed;

    /** Die HTTP-Antwort muss bereits vor der ersten Chat-Nachricht als Stream geöffnet sein. */
    public SseClient(TestChat application, UUID roomId, String cursor) throws Exception {
        mapper = application.mapper;
        HttpClient.Builder clientBuilder = HttpClient.newBuilder();
        Duration connectTimeout = Duration.ofSeconds(5);
        clientBuilder = clientBuilder.connectTimeout(connectTimeout);
        client = clientBuilder.build();
        URI address = URI.create(application.baseUrl + "/stream?roomId=" + roomId);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(address);
        requestBuilder = requestBuilder.GET();
        Duration requestTimeout = Duration.ofSeconds(10);
        requestBuilder = requestBuilder.timeout(requestTimeout);
        if (cursor != null) {
            requestBuilder = requestBuilder.header("Last-Event-ID", cursor);
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse.BodyHandler<InputStream> bodyHandler = HttpResponse.BodyHandlers.ofInputStream();
        HttpResponse<InputStream> response = client.send(request, bodyHandler);
        input = response.body();
        int statusCode = response.statusCode();
        assertEquals(200, statusCode);
        HttpHeaders headers = response.headers();
        Optional<String> contentTypeHeader = headers.firstValue("Content-Type");
        String contentType = contentTypeHeader.orElse("");
        boolean eventStream = contentType.startsWith("text/event-stream");
        assertTrue(eventStream);
        Thread.Builder.OfVirtual threadBuilder = Thread.ofVirtual();
        threadBuilder = threadBuilder.name("test-sse-reader");
        reader = threadBuilder.start(this::read);
    }

    /** Liefert genau die erwartete Anzahl oder eine verständliche Zeitüberschreitung. */
    public List<ChatMessage> take(int count, Duration timeout) throws Exception {
        List<ChatMessage> result = new ArrayList<>();
        long deadline = System.nanoTime() + timeout.toNanos();
        while (result.size() < count) {
            Throwable parserFailure = failure.get();
            assertNull(parserFailure, "SSE parser failed");
            long remaining = deadline - System.nanoTime();
            int receivedCount = result.size();
            assertTrue(remaining > 0, "Missing SSE messages: expected " + count + ", got " + receivedCount);
            ChatMessage message = messages.poll(remaining, TimeUnit.NANOSECONDS);
            assertNotNull(message, "SSE message did not arrive");
            result.add(message);
        }
        return result;
    }

    /** Prüft auch nach der erwarteten Anzahl auf verbotene zusätzliche Ereignisse. */
    public void assertQuiet(Duration duration) throws Exception {
        Throwable parserFailure = failure.get();
        assertNull(parserFailure, "SSE parser failed");
        long milliseconds = duration.toMillis();
        ChatMessage extra = messages.poll(milliseconds, TimeUnit.MILLISECONDS);
        assertNull(extra, "Unexpected additional SSE message");
    }

    /** BufferedReader behandelt UTF-8 und beliebig aufgeteilte TCP-Lieferungen. */
    private void read() {
        try {
            InputStreamReader utf8 = new InputStreamReader(input, StandardCharsets.UTF_8);
            BufferedReader lines = new BufferedReader(utf8);
            String eventId = null;
            StringBuilder data = new StringBuilder();
            String line;
            while (!closed && (line = lines.readLine()) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        String json = data.toString();
                        ChatMessage message = mapper.readValue(json, ChatMessage.class);
                        UUID messageId = message.id();
                        String expectedId = messageId.toString();
                        assertEquals(expectedId, eventId);
                        messages.add(message);
                    }
                    eventId = null;
                    data.setLength(0);
                } else if (line.startsWith("id:")) {
                    eventId = fieldValue(line, 3);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    String value = fieldValue(line, 5);
                    data.append(value);
                }
            }
        } catch (Throwable exception) {
            if (!closed) {
                failure.set(exception);
            }
        }
    }

    /** SSE erlaubt genau ein optionales Leerzeichen direkt nach dem Doppelpunkt. */
    private String fieldValue(String line, int offset) {
        String value = line.substring(offset);
        if (value.startsWith(" ")) {
            return value.substring(1);
        }
        return value;
    }

    /** Ein geschlossener Testclient darf keine Leseraufgabe im Hintergrund behalten. */
    @Override
    public void close() throws Exception {
        closed = true;
        input.close();
        reader.interrupt();
        client.shutdownNow();
    }
}
