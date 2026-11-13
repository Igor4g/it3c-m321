package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import ch.benedict.m321.chatservice.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die interne REST-Schnittstelle des chat-service.
 *
 * Erreichbar ist sie nur aus dem Docker-Netz. Gateway und Tokenprüfung
 * gehören zum späteren Gesamtprojekt, nicht zu dieser Bewertung.
 */
@RestController
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * Nimmt eine Nachricht entgegen.
     *
     * Antwort ist 202 und nicht 201, weil die Nachricht angenommen, aber
     * noch nirgends gespeichert ist. Der Statuscode sagt genau das aus,
     * was das System tut.
     */
    @PostMapping("/messages")
    public ResponseEntity<AcceptedResponse> send(@Valid @RequestBody SendMessageRequest request) {
        AcceptedResponse response = messageService.accept(request);
        return ResponseEntity.accepted().body(response);
    }
}
