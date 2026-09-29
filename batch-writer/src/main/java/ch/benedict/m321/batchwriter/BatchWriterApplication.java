package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des eigenständigen Schreibdienstes.
 * Queue-Verarbeitung und Datenbankzugriff werden schrittweise ergänzt.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /**
     * Startet Spring und lädt die Konfiguration des Hintergrunddienstes.
     * Der Writer stellt keine HTTP-Schnittstelle bereit.
     */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
