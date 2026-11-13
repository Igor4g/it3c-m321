package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.UUID;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Genau ein Ausgabeweg je Verbindung verhindert Überholen und blockierte fremde Clients. */
public class StreamSession {

    private final UUID roomId;
    private final SseEmitter emitter;
    private final BlockingQueue<ChatMessage> pending;
    private final Consumer<StreamSession> onClosed;
    private final int capacity;
    private final LinkedHashSet<UUID> seen = new LinkedHashSet<>();
    private Set<UUID> excluded = Set.of();
    private final AtomicBoolean closed = new AtomicBoolean();
    private boolean overflowed;
    private volatile Thread worker;

    /** Der Puffer gehört einer Verbindung und wird nicht mit anderen Clients geteilt. */
    public StreamSession(UUID roomId, int capacity, SseEmitter emitter,
                         Consumer<StreamSession> onClosed) {
        this.roomId = roomId;
        this.emitter = emitter;
        this.pending = new ArrayBlockingQueue<>(capacity);
        this.capacity = capacity;
        this.onClosed = onClosed;
        emitter.onCompletion(this::close);
        emitter.onTimeout(this::close);
        emitter.onError(exception -> close());
    }

    /** Startet erst nach Registrierung, damit neue Nachrichten bereits einen Platz haben. */
    public void start() {
        if (closed.get()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Connection closed during replay");
        }
        worker = Thread.ofVirtual().name("chat-stream").start(this::writeMessages);
    }

    /** Wartet nie auf Netzwerk-I/O; ein voller Puffer wird dem Aufrufer gemeldet. */
    public synchronized boolean offer(ChatMessage message) {
        if (closed.get() || overflowed) {
            return false;
        }
        UUID messageId = message.id();
        if (excluded.contains(messageId) || seen.contains(messageId)) {
            return true;
        }
        if (!pending.offer(message)) {
            overflowed = true;
            return false;
        }
        seen.add(messageId);
        if (seen.size() > capacity) {
            seen.removeFirst();
        }
        return true;
    }

    /** Vor Worker-Start sind dies genau die während des SQL-Ladens eingetroffenen Ereignisse. */
    public synchronized List<ChatMessage> pendingSnapshot() {
        return new ArrayList<>(pending);
    }

    /** Ersetzt unter der Registrierungssperre den Übergabepuffer durch die vollständige geordnete Folge. */
    public synchronized void prepareReplay(List<ChatMessage> messages, Set<UUID> excludedIds) {
        if (closed.get() || overflowed || messages.size() > capacity) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Replay buffer exceeded");
        }
        pending.clear();
        seen.clear();
        // Diese begrenzten alten IDs dürfen nicht aus dem rollenden Live-Fenster herausfallen.
        excluded = Set.copyOf(excludedIds);
        for (ChatMessage message : messages) {
            offer(message);
        }
    }

    /** Die Raumkennung bestimmt die Empfänger, nicht der Name des Absenders. */
    public UUID roomId() {
        return roomId;
    }

    /** Spring MVC übernimmt diesen Emitter als offene HTTP-Antwort. */
    public SseEmitter emitter() {
        return emitter;
    }

    /** Entfernt die Sitzung sofort, ohne auf einen möglicherweise blockierten Socket zu warten. */
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        pending.clear();
        onClosed.accept(this);
        Thread activeWorker = worker;
        if (activeWorker != null && activeWorker != Thread.currentThread()) {
            activeWorker.interrupt();
        }
    }

    /** Nur dieser Worker schreibt; Kommentare halten die Verbindung ohne zusätzliche Chat-Events offen. */
    private void writeMessages() {
        try {
            SseEmitter.SseEventBuilder ready = SseEmitter.event().comment("connected");
            emitter.send(ready);
            while (!closed.get()) {
                ChatMessage message = pending.poll(15, TimeUnit.SECONDS);
                if (message == null) {
                    SseEmitter.SseEventBuilder heartbeat = SseEmitter.event().comment("heartbeat");
                    emitter.send(heartbeat);
                } else {
                    String messageId = message.id().toString();
                    SseEmitter.SseEventBuilder event = SseEmitter.event()
                            .id(messageId).data(message, MediaType.APPLICATION_JSON);
                    emitter.send(event);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException | IllegalStateException exception) {
            // Ein getrenntes Gegenüber beendet nur seine eigene Sitzung.
        } finally {
            close();
            emitter.complete();
        }
    }
}
