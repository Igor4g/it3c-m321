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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Startet eine echte HTTP-Anwendung; zwei Objekte sind wirklich zwei getrennte Instanzen. */
public class TestChat implements AutoCloseable {

    public final ConfigurableApplicationContext context;
    public final ObjectMapper mapper;
    public final HttpClient client;
    public final String baseUrl;

    /** Kommandozeilenargumente überschreiben die festen Ports der Anwendung zuverlässig. */
    public TestChat(RabbitMQContainer broker, String... additionalArguments) {
        List<String> arguments = new ArrayList<>();
        arguments.add("--server.port=0");
        arguments.add("--spring.rabbitmq.host=" + broker.getHost());
        arguments.add("--spring.rabbitmq.port=" + broker.getAmqpPort());
        arguments.add("--spring.rabbitmq.username=" + broker.getAdminUsername());
        arguments.add("--spring.rabbitmq.password=" + broker.getAdminPassword());
        arguments.add("--logging.level.root=WARN");
        arguments.add("--logging.level.ch.benedict.m321=WARN");
        arguments.add("--debug=false");
        arguments.add("--spring.main.banner-mode=off");
        for (String argument : additionalArguments) {
            arguments.add(argument);
        }
        String[] properties = arguments.toArray(String[]::new);
        context = new SpringApplicationBuilder(ChatServiceApplication.class).run(properties);
        ServletWebServerApplicationContext server = (ServletWebServerApplicationContext) context;
        int port = server.getWebServer().getPort();
        baseUrl = "http://localhost:" + port;
        mapper = context.getBean(ObjectMapper.class);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** Sendet über den echten POST-Vertrag und liefert die erwarteten sechs Werte zurück. */
    public ChatMessage send(UUID roomId, String sender, String content) throws Exception {
        SendMessageRequest message = new SendMessageRequest(roomId, sender, sender, content);
        String json = mapper.writeValueAsString(message);
        URI address = URI.create(baseUrl + "/messages");
        HttpRequest request = HttpRequest.newBuilder(address)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(202, response.statusCode(), response.body());
        AcceptedResponse accepted = mapper.readValue(response.body(), AcceptedResponse.class);
        return new ChatMessage(accepted.id(), roomId, sender, sender, content, accepted.sentAt());
    }

    /** Einfacher HTTP-Aufruf für negative Parameter- und Statusprüfungen. */
    public HttpResponse<String> get(String path, String cursor) throws Exception {
        URI address = URI.create(baseUrl + path);
        HttpRequest.Builder request = HttpRequest.newBuilder(address).GET();
        if (cursor != null) {
            request.header("Last-Event-ID", cursor);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Beendet zuerst HTTP-Arbeit und dann die eigene Anwendung samt exklusiver Queue. */
    @Override
    public void close() {
        client.shutdownNow();
        context.close();
    }
}
