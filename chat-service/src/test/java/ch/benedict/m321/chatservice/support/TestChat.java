package ch.benedict.m321.chatservice.support;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.RabbitMQContainer;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Startet eine echte HTTP-Anwendung; zwei Objekte sind wirklich zwei getrennte Instanzen. */
public class TestChat implements AutoCloseable {

    // Gleiche Testfristen werden gemeinsam benannt statt pro Aufruf wiederholt.
    private static final Duration SECONDS_5 = Duration.ofSeconds(5);


    public final ConfigurableApplicationContext context;
    public final ObjectMapper mapper;
    public final HttpClient client;
    public final String baseUrl;

    /** Kommandozeilenargumente überschreiben die festen Ports der Anwendung zuverlässig. */
    public TestChat(RabbitMQContainer broker, String... additionalArguments) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--server.port=0");
        String brokerHost = broker.getHost();
        arguments.add("--spring.rabbitmq.host=" + brokerHost);
        int brokerPort = broker.getAmqpPort();
        arguments.add("--spring.rabbitmq.port=" + brokerPort);
        String brokerUsername = broker.getAdminUsername();
        arguments.add("--spring.rabbitmq.username=" + brokerUsername);
        String brokerPassword = broker.getAdminPassword();
        arguments.add("--spring.rabbitmq.password=" + brokerPassword);
        arguments.add("--logging.level.root=WARN");
        arguments.add("--logging.level.ch.benedict.m321=WARN");
        arguments.add("--debug=false");
        arguments.add("--spring.main.banner-mode=off");
        for (String argument : additionalArguments) {
            arguments.add(argument);
        }
        String[] properties = arguments.toArray(String[]::new);
        SpringApplicationBuilder applicationBuilder = new SpringApplicationBuilder(ChatServiceApplication.class);
        context = applicationBuilder.run(properties);
        ServletWebServerApplicationContext server = (ServletWebServerApplicationContext) context;
        var webServer = server.getWebServer();
        int port = webServer.getPort();
        baseUrl = "http://localhost:" + port;
        mapper = context.getBean(ObjectMapper.class);
        HttpClient.Builder clientBuilder = HttpClient.newBuilder();
        clientBuilder = clientBuilder.connectTimeout(SECONDS_5);
        client = clientBuilder.build();
    }

    /** Sendet über den echten POST-Vertrag und liefert die erwarteten sechs Werte zurück. */
    public ChatMessage send(UUID roomId, String sender, String content) throws Exception {
        SendMessageRequest message = new SendMessageRequest(roomId, sender, sender, content);
        String json = mapper.writeValueAsString(message);
        URI address = URI.create(baseUrl + "/messages");
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(address);
        requestBuilder = requestBuilder.timeout(SECONDS_5);
        requestBuilder = requestBuilder.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.ofString(json);
        requestBuilder = requestBuilder.POST(bodyPublisher);
        HttpRequest request = requestBuilder.build();
        HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
        HttpResponse<String> response = client.send(request, bodyHandler);
        int statusCode = response.statusCode();
        String responseBody = response.body();
        assertEquals(202, statusCode, responseBody);
        AcceptedResponse accepted = mapper.readValue(responseBody, AcceptedResponse.class);
        UUID messageId = accepted.id();
        Instant sentAt = accepted.sentAt();
        return new ChatMessage(messageId, roomId, sender, sender, content, sentAt);
    }

    /** Einfacher HTTP-Aufruf für negative Parameter- und Statusprüfungen. */
    public HttpResponse<String> get(String path, String cursor) throws Exception {
        URI address = URI.create(baseUrl + path);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(address);
        requestBuilder = requestBuilder.GET();
        Duration requestTimeout = Duration.ofSeconds(10);
        requestBuilder = requestBuilder.timeout(requestTimeout);
        if (cursor != null) {
            requestBuilder = requestBuilder.header("Last-Event-ID", cursor);
        }
        HttpRequest request = requestBuilder.build();
        HttpResponse.BodyHandler<String> bodyHandler = HttpResponse.BodyHandlers.ofString();
        return client.send(request, bodyHandler);
    }

    /** Beendet zuerst HTTP-Arbeit und dann die eigene Anwendung samt exklusiver Queue. */
    @Override
    public void close() {
        client.shutdownNow();
        context.close();
    }
}
