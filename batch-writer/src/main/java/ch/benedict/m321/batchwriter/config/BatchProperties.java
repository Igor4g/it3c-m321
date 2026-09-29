package ch.benedict.m321.batchwriter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bündelt Batch-Grenzen und die Pause nach einem fehlgeschlagenen Schreibversuch. */
@ConfigurationProperties(prefix = "batch")
public record BatchProperties(int size, long timeoutMs, long retryDelayMs) {

    /** Ungültige Grenzen sollen beim Start auffallen, nicht erst bei der Verarbeitung. */
    public BatchProperties {
        if (size < 1 || timeoutMs < 1 || retryDelayMs < 1) {
            throw new IllegalArgumentException("Batch size, timeout and retry delay must be positive");
        }
    }
}
