package ch.benedict.m321.chatservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Begrenzt lokale Puffer, damit langsame oder abwesende Clients nicht unbegrenzt RAM belegen. */
@ConfigurationProperties(prefix = "live")
public record LiveProperties(
        @DefaultValue("10000") int historySize,
        @DefaultValue("10000") int clientBufferSize,
        @DefaultValue("10000") int replayLimit) {

    /** Unbrauchbare Grenzen werden beim Start gemeldet statt erst unter Last. */
    public LiveProperties {
        if (historySize < 1 || clientBufferSize < 1 || replayLimit < 1) {
            throw new IllegalArgumentException("Live buffer limits must be positive");
        }
    }
}
