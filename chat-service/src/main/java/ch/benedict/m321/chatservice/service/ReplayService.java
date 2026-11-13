package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.LiveProperties;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.repository.MessageHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Bereitet Nachlieferung vor, ohne während der SQL-Abfrage die Live-Verteilung zu sperren. */
@Service
@RequiredArgsConstructor
public class ReplayService {

    private final MessageHistoryRepository history;
    private final LiveProperties properties;

    /** Diese unveränderliche Vorbereitung wird erst mit den inzwischen eingetroffenen Ereignissen verbunden. */
    public record Prepared(UUID roomId, ChatMessage cursor, List<ChatMessage> recent, List<ChatMessage> stored) {
    }

    /** Auch ein RAM-Cursor benötigt gemäss Vertrag eine erfolgreiche DB-Abfrage. */
    public Prepared load(UUID roomId, UUID cursorId, List<ChatMessage> recent) {
        try {
            ChatMessage cursor = history.find(cursorId);
            if (cursor == null) {
                cursor = find(recent, cursorId);
            }
            if (cursor == null || !roomId.equals(cursor.roomId())) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown cursor in this room");
            }
            int maximumRows = Math.addExact(properties.replayLimit(), 1);
            List<ChatMessage> stored = history.after(roomId, cursor, maximumRows);
            if (stored.size() > properties.replayLimit()) {
                throw limitExceeded();
            }
            return new Prepared(roomId, cursor, recent, stored);
        } catch (DataAccessException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "History temporarily unavailable");
        }
    }

    /** IDs verbinden SQL, RAM und Übergabe; der Cursor und bekannte Vorgänger werden ausgeschlossen. */
    public List<ChatMessage> merge(Prepared prepared, List<ChatMessage> arrived) {
        Set<UUID> excluded = predecessors(prepared);
        Map<UUID, ChatMessage> result = new LinkedHashMap<>();
        for (ChatMessage message : prepared.stored()) {
            add(result, message, prepared, excluded);
        }
        boolean cursorInBuffer = find(prepared.recent(), prepared.cursor().id()) != null;
        for (ChatMessage message : prepared.recent()) {
            if (cursorInBuffer || compare(message, prepared.cursor()) > 0) {
                add(result, message, prepared, excluded);
            }
        }
        // Neu eingetroffene Ereignisse können trotz späteren Empfangs eine ältere Uhrzeit tragen.
        for (ChatMessage message : arrived) {
            add(result, message, prepared, excluded);
        }
        if (result.size() > properties.replayLimit() || result.size() > properties.clientBufferSize()) {
            throw limitExceeded();
        }
        List<ChatMessage> ordered = new ArrayList<>(result.values());
        ordered.sort(this::compare);
        return ordered;
    }

    /** Die beobachtete RAM-Position verhindert, dass bekannte Vorgänger durch SQL erneut erscheinen. */
    private Set<UUID> predecessors(Prepared prepared) {
        Set<UUID> excluded = new HashSet<>();
        UUID cursorId = prepared.cursor().id();
        if (find(prepared.recent(), cursorId) != null) {
            for (ChatMessage message : prepared.recent()) {
                excluded.add(message.id());
                if (cursorId.equals(message.id())) {
                    break;
                }
            }
        }
        excluded.add(cursorId);
        return excluded;
    }

    /** Aktuelle RAM-Daten erhalten beim ID-Abgleich ihren ursprünglichen Nachrichteninhalt. */
    private void add(Map<UUID, ChatMessage> result, ChatMessage message,
                     Prepared prepared, Set<UUID> excluded) {
        if (prepared.roomId().equals(message.roomId()) && !excluded.contains(message.id())) {
            result.put(message.id(), message);
        }
    }

    /** Ein begrenzter linearer Durchlauf ist hier verständlicher als ein zusätzlicher unbeschränkter Index. */
    private ChatMessage find(List<ChatMessage> messages, UUID id) {
        for (ChatMessage message : messages) {
            if (id.equals(message.id())) {
                return message;
            }
        }
        return null;
    }

    /** Kanonischer UUID-Text entspricht der SQL-Reihenfolge auch oberhalb des Vorzeichenbits. */
    private int compare(ChatMessage first, ChatMessage second) {
        int byTime = first.sentAt().compareTo(second.sentAt());
        if (byTime != 0) {
            return byTime;
        }
        String firstId = first.id().toString();
        String secondId = second.id().toString();
        return firstId.compareTo(secondId);
    }

    /** Ein Fehler ist ehrlicher als ein abgeschnittener, angeblich vollständiger Verlauf. */
    private ResponseStatusException limitExceeded() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Replay limit exceeded");
    }
}
