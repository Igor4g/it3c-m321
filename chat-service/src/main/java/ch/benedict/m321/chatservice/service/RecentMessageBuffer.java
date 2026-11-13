package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.LiveProperties;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Hält auch ohne Clients die jüngsten Broker-Ereignisse bis zur verzögerten Speicherung bereit. */
@Component
@RequiredArgsConstructor
public class RecentMessageBuffer {

    private final LiveProperties properties;
    private final LinkedHashMap<UUID, ChatMessage> messages = new LinkedHashMap<>();

    /** Bekannte IDs werden nicht nochmals verteilt; bei Überlauf fällt die älteste lokale Kopie weg. */
    public synchronized boolean add(ChatMessage message) {
        if (messages.containsKey(message.id())) {
            return false;
        }
        messages.put(message.id(), message);
        if (messages.size() > properties.historySize()) {
            messages.pollFirstEntry();
        }
        return true;
    }

    /** Eine Kopie lässt sich nach Freigabe der Sperre bearbeiten, ohne die Historie zu verändern. */
    public synchronized List<ChatMessage> snapshot() {
        return new ArrayList<>(messages.values());
    }

    /** Ein noch nicht gespeicherter Cursor kann innerhalb des begrenzten aktuellen Fensters liegen. */
    public synchronized ChatMessage find(UUID messageId) {
        return messages.get(messageId);
    }
}
