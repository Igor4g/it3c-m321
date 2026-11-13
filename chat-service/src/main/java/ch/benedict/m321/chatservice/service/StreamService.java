package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.LiveProperties;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Ordnet lokale Verbindungen ihren Räumen zu; der Live-Weg benötigt keine Datenbank. */
@Service
@RequiredArgsConstructor
public class StreamService {

    private final LiveProperties properties;
    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final RecentMessageBuffer recentMessages;
    private final ReplayService replayService;
    private final Object monitor = new Object();
    private final Map<UUID, Set<StreamSession>> clients = new HashMap<>();

    /** Registriert vor dem ersten Schreiben, damit zwischen Öffnen und Zustellen keine Lücke entsteht. */
    public SseEmitter open(UUID roomId) {
        return open(roomId, null);
    }

    /** Die Sitzung empfängt schon während der SQL-Abfrage; ihr Ausgabe-Worker startet erst danach. */
    public SseEmitter open(UUID roomId, UUID cursorId) {
        awaitConsumer();
        SseEmitter emitter = new SseEmitter(0L);
        int clientCapacity = properties.clientBufferSize();
        StreamSession session = new StreamSession(roomId, clientCapacity, emitter, this::remove);
        List<ChatMessage> snapshot = List.of();
        synchronized (monitor) {
            Set<StreamSession> roomClients = clients.get(roomId);
            if (roomClients == null) {
                roomClients = new HashSet<>();
                clients.put(roomId, roomClients);
            }
            roomClients.add(session);
            if (cursorId != null) {
                snapshot = recentMessages.snapshot();
            }
        }
        try {
            if (cursorId != null) {
                ReplayService.Prepared prepared = replayService.load(roomId, cursorId, snapshot);
                synchronized (monitor) {
                    List<ChatMessage> arrived = session.pendingSnapshot();
                    List<ChatMessage> ordered = replayService.merge(prepared, arrived);
                    Set<UUID> excludedIds = prepared.excluded();
                    session.prepareReplay(ordered, excludedIds);
                }
            }
            session.start();
            return emitter;
        } catch (RuntimeException exception) {
            session.close();
            throw exception;
        }
    }

    /** Übergibt nur passende Raum-Nachrichten; langsame Sitzungen werden ausserhalb der Sperre beendet. */
    public void receive(ChatMessage message) {
        List<StreamSession> fullSessions = new ArrayList<>();
        synchronized (monitor) {
            if (!recentMessages.add(message)) {
                return;
            }
            Set<StreamSession> roomClients = clients.get(message.roomId());
            if (roomClients == null) {
                return;
            }
            for (StreamSession session : roomClients) {
                boolean accepted = session.offer(message);
                if (!accepted) {
                    fullSessions.add(session);
                }
            }
        }
        for (StreamSession session : fullSessions) {
            session.close();
        }
    }

    /** Erlaubt Tests und Betriebskontrolle, ohne eine zusätzliche HTTP-Schnittstelle zu öffnen. */
    public int connectionCount() {
        int count = 0;
        synchronized (monitor) {
            for (Set<StreamSession> roomClients : clients.values()) {
                count += roomClients.size();
            }
        }
        return count;
    }

    /** Eine leere Raumgruppe wird entfernt, damit auch wechselnde Räume keinen Speicher behalten. */
    private void remove(StreamSession session) {
        synchronized (monitor) {
            Set<StreamSession> roomClients = clients.get(session.roomId());
            if (roomClients != null) {
                roomClients.remove(session);
                if (roomClients.isEmpty()) {
                    clients.remove(session.roomId());
                }
            }
        }
    }

    /** Ein erreichbarer HTTP-Port allein beweist noch keine gebundene Consumer-Queue. */
    private void awaitConsumer() {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            MessageListenerContainer container = listenerRegistry.getListenerContainer("live-delivery");
            if (container instanceof SimpleMessageListenerContainer listener
                    && listener.getActiveConsumerCount() > 0) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Live consumer interrupted");
            }
        }
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Live consumer not ready");
    }

    /** Beim Beenden einer Instanz bleiben keine eigenen Client-Worker absichtlich aktiv. */
    @PreDestroy
    public void close() {
        List<StreamSession> sessions = new ArrayList<>();
        synchronized (monitor) {
            for (Set<StreamSession> roomClients : clients.values()) {
                sessions.addAll(roomClients);
            }
            clients.clear();
        }
        for (StreamSession session : sessions) {
            session.close();
        }
    }
}
