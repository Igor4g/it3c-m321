package ch.benedict.m321.batchwriter.messaging;

/**
 * Kennzeichnet eine Nachricht, deren unveränderter Inhalt nicht verarbeitet werden kann.
 * Der Consumer unterscheidet damit Datenfehler für die DLQ von wiederholbaren DB-Fehlern.
 */
public class InvalidMessageException extends RuntimeException {

    /** Meldet eine Vertragsverletzung, ohne den Chat-Inhalt in die Fehlermeldung zu kopieren. */
    public InvalidMessageException(String message) {
        super(message);
    }

    /** Behält die technische Ursache bei, etwa einen JSON- oder Zeichenkodierungsfehler. */
    public InvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
