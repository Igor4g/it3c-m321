package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.LiveProperties;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.repository.MessageHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
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
    private final PlatformTransactionManager transactionManager;

    /** Der Lesestand enthält auch bekannte alte IDs für die spätere Live-Übergabe. */
    public record Prepared(UUID roomId, ChatMessage cursor, List<ChatMessage> recent,
                           List<ChatMessage> stored, Set<UUID> excluded) {
    }

    /** Auch Fehler beim Transaktionsbeginn müssen als 503 statt als interner Fehler enden. */
    public Prepared load(UUID roomId, UUID cursorId, List<ChatMessage> recent) {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        try {
            return transaction.execute(status -> readHistory(roomId, cursorId, recent));
        } catch (DataAccessException | TransactionException exception) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "History temporarily unavailable");
        }
    }

    /** Ein gemeinsamer SQL-Lesestand trennt bekannte Vorgänger von erst während des Ladens gespeicherten IDs. */
    private Prepared readHistory(UUID roomId, UUID cursorId, List<ChatMessage> recent) {
        history.beginSnapshot();
        ChatMessage cursor = loadCursor(roomId, cursorId, recent);
        int replayLimit = properties.replayLimit();
        int maximumRows = Math.addExact(replayLimit, 1);
        List<ChatMessage> stored = history.after(roomId, cursor, maximumRows);
        if (stored.size() > replayLimit) {
            throw limitExceeded();
        }
        Set<UUID> excluded = predecessors(roomId, cursorId, recent);
        int historySize = properties.historySize();
        List<UUID> storedPredecessors = history.beforeIds(roomId, cursor, historySize);
        for (UUID id : storedPredecessors) {
            if (excluded.size() >= historySize) {
                break;
            }
            excluded.add(id);
        }
        return new Prepared(roomId, cursor, recent, stored, excluded);
    }

    /** Die ID kann noch im RAM liegen; nur ein Cursor desselben Raums darf Geschichte öffnen. */
    private ChatMessage loadCursor(UUID roomId, UUID cursorId, List<ChatMessage> recent) {
        ChatMessage cursor = history.find(cursorId);
        if (cursor == null) {
            cursor = find(recent, cursorId);
        }
        UUID cursorRoom = null;
        if (cursor != null) {
            cursorRoom = cursor.roomId();
        }
        if (!roomId.equals(cursorRoom)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown cursor in this room");
        }
        return cursor;
    }

    /** Erst geordnete Historie ausgeben, danach neue Übergabe-Ereignisse FIFO anhängen. */
    public List<ChatMessage> merge(Prepared prepared, List<ChatMessage> arrived) {
        List<ChatMessage> ordered = historicalMessages(prepared);
        // putIfAbsent erhält bei Überschneidungen die schon vorbereitete historische Kopie.
        Map<UUID, ChatMessage> complete = new LinkedHashMap<>();
        for (ChatMessage message : ordered) {
            add(complete, message, prepared);
        }
        for (ChatMessage message : arrived) {
            add(complete, message, prepared);
        }
        if (complete.size() > properties.replayLimit() || complete.size() > properties.clientBufferSize()) {
            throw limitExceeded();
        }
        Collection<ChatMessage> messages = complete.values();
        return new ArrayList<>(messages);
    }

    /** Diese Sortierung betrifft ausschliesslich die vor der Übergabe vorbereitete Historie. */
    private List<ChatMessage> historicalMessages(Prepared prepared) {
        Map<UUID, ChatMessage> historyById = new LinkedHashMap<>();
        List<ChatMessage> stored = prepared.stored();
        for (ChatMessage message : stored) {
            add(historyById, message, prepared);
        }
        List<ChatMessage> recent = prepared.recent();
        ChatMessage cursor = prepared.cursor();
        UUID cursorId = cursor.id();
        boolean cursorInBuffer = find(recent, cursorId) != null;
        for (ChatMessage message : recent) {
            if (cursorInBuffer || compare(message, cursor) > 0) {
                add(historyById, message, prepared);
            }
        }
        Collection<ChatMessage> messages = historyById.values();
        List<ChatMessage> ordered = new ArrayList<>(messages);
        ordered.sort(this::compare);
        return ordered;
    }

    /** Beobachtete RAM-Vorgänger haben Vorrang; die Ausschlussmenge bleibt samt Cursor begrenzt. */
    private Set<UUID> predecessors(UUID roomId, UUID cursorId, List<ChatMessage> recent) {
        Set<UUID> excluded = new HashSet<>();
        ChatMessage bufferedCursor = find(recent, cursorId);
        if (bufferedCursor != null) {
            for (ChatMessage message : recent) {
                UUID messageRoom = message.roomId();
                UUID messageId = message.id();
                if (roomId.equals(messageRoom)) {
                    excluded.add(messageId);
                }
                if (cursorId.equals(messageId)) {
                    break;
                }
            }
        }
        excluded.add(cursorId);
        return excluded;
    }

    /** ID-Abgleich entfernt alte Kopien und Überschneidungen, ohne neue alte Zeitstempel zu verwerfen. */
    private void add(Map<UUID, ChatMessage> result, ChatMessage message, Prepared prepared) {
        UUID roomId = prepared.roomId();
        UUID messageRoom = message.roomId();
        UUID messageId = message.id();
        Set<UUID> excluded = prepared.excluded();
        if (roomId.equals(messageRoom) && !excluded.contains(messageId)) {
            result.putIfAbsent(messageId, message);
        }
    }

    /** Ein begrenzter linearer Durchlauf braucht keinen weiteren dauerhaften Index. */
    private ChatMessage find(List<ChatMessage> messages, UUID id) {
        for (ChatMessage message : messages) {
            UUID messageId = message.id();
            if (id.equals(messageId)) {
                return message;
            }
        }
        return null;
    }

    /** Nur Historie wird sortiert; kanonischer UUID-Text entspricht auch bei grossen IDs der SQL-Reihenfolge. */
    private int compare(ChatMessage first, ChatMessage second) {
        Instant firstTime = first.sentAt();
        Instant secondTime = second.sentAt();
        int byTime = firstTime.compareTo(secondTime);
        if (byTime != 0) {
            return byTime;
        }
        UUID firstUuid = first.id();
        UUID secondUuid = second.id();
        String firstId = firstUuid.toString();
        String secondId = secondUuid.toString();
        return firstId.compareTo(secondId);
    }

    /** Ein Fehler ist ehrlicher als ein abgeschnittener, angeblich vollständiger Verlauf. */
    private ResponseStatusException limitExceeded() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Replay limit exceeded");
    }
}
