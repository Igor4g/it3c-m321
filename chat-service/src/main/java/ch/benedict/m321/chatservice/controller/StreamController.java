package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.service.StreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** Bietet den vorgegebenen SSE-Vertrag direkt im internen Chat-Service an. */
@RestController
@RequiredArgsConstructor
public class StreamController {

    private final StreamService streamService;

    /** Die Raumkennung bestimmt den Stream; die Verbindung bleibt für spätere Ereignisse offen. */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam String roomId,
                            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        UUID room = parseUuid(roomId);
        UUID cursor = null;
        if (lastEventId != null) {
            cursor = parseUuid(lastEventId);
        }
        return streamService.open(room, cursor);
    }

    /** Verkürzte UUID-Schreibweisen sind kein Teil unseres HTTP-Vertrags. */
    private UUID parseUuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            String canonical = id.toString();
            if (!canonical.equalsIgnoreCase(value)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
            return id;
        } catch (IllegalArgumentException exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid UUID");
        }
    }
}
