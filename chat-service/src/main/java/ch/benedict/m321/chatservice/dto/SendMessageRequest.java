package ch.benedict.m321.chatservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Eingabevertrag für POST /messages im internen Docker-Netz.
 *
 * Bewusst OHNE id und ohne Zeitstempel: beides vergibt der Server.
 * So erhalten neue Nachrichten eine eigene Identität unabhängig vom Client.
 *
 * @param roomId     der Raum, in den die Nachricht gehört
 * @param senderId   die übergebene Kennung des Absenders, später aus Keycloak
 * @param senderName der Anzeigename, damit die Historie lesbar bleibt
 * @param content    der Text der Nachricht
 */
public record SendMessageRequest(
        @NotNull UUID roomId,
        @NotBlank String senderId,
        @NotBlank String senderName,
        @NotBlank String content) {
}
