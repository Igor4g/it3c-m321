package ch.benedict.m321.batchwriter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bündelt die beiden Einstellungen, die die Sammlung einer Lieferung begrenzen. */
@ConfigurationProperties(prefix = "batch")
public record BatchProperties(int size, long timeoutMs) {

    /** Ungültige Grenzen sollen beim Start auffallen, nicht erst bei der Verarbeitung. */
    public BatchProperties {
        if (size < 1 || timeoutMs < 1) {
            throw new IllegalArgumentException("Batch size and timeout must be positive");
        }
    }
}
