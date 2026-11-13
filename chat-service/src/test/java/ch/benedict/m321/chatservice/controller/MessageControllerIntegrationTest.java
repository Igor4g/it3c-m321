package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Der Weg von aussen nach innen, einmal ganz durch: HTTP rein,
 * Nachricht in der Queue raus.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MessageControllerIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** HTTP-Annahme und tatsächliche Veröffentlichung müssen dieselbe Nachrichten-ID verwenden. */
    @Test
    void acceptsMessageAndPutsItOnTheQueue() throws Exception {
        String requestBody = """
                {
                  "roomId": "3f2b1c4e-0000-0000-0000-000000000001",
                  "senderId": "anna",
                  "senderName": "Anna Muster",
                  "content": "Hallo zusammen"
                }
                """;

        var postResult = post("/messages");
        var contentTypeResult = postResult
                        .contentType(MediaType.APPLICATION_JSON);
        var contentResult = contentTypeResult
                        .content(requestBody);
        var performResult = mockMvc.perform(contentResult);
        var statusResult = status();
        var isAcceptedResult = statusResult.isAccepted();
        var andExpectResult = performResult
                .andExpect(isAcceptedResult);
        var jsonPathResult = jsonPath("$.id");
        var existsResult = jsonPathResult.exists();
        var andExpectResult2 = andExpectResult
                .andExpect(existsResult);
        var jsonPathResult2 = jsonPath("$.sentAt");
        var existsResult2 = jsonPathResult2.exists();
        andExpectResult2
                .andExpect(existsResult2);

        ParameterizedTypeReference<ChatMessage> targetType = new ParameterizedTypeReference<>() {
        };
        ChatMessage message = rabbitTemplate.receiveAndConvert(
                QueueNames.PERSIST_QUEUE, 5000, targetType);

        assertNotNull(message);
        var contentResult2 = message.content();
        assertEquals("Hallo zusammen", contentResult2);
        var senderNameResult = message.senderName();
        assertEquals("Anna Muster", senderNameResult);
    }

    /** Ein leerer Inhalt wird abgelehnt, bevor er im verteilten System weiterverarbeitet wird. */
    @Test
    void rejectsEmptyContent() throws Exception {
        String requestBody = """
                {
                  "roomId": "3f2b1c4e-0000-0000-0000-000000000001",
                  "senderId": "anna",
                  "senderName": "Anna Muster",
                  "content": "   "
                }
                """;

        var postResult = post("/messages");
        var contentTypeResult = postResult
                        .contentType(MediaType.APPLICATION_JSON);
        var contentResult = contentTypeResult
                        .content(requestBody);
        var performResult = mockMvc.perform(contentResult);
        var statusResult = status();
        var isBadRequestResult = statusResult.isBadRequest();
        performResult
                .andExpect(isBadRequestResult);
    }
}
