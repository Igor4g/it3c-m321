package ch.benedict.m321.batchwriter.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Die geprüfte Nachricht für den Schreibweg.
 * Der gemeinsame Vertrag ist JSON; dieser record gehört nur dem Writer.
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
