package ch.benedict.m321.chatservice.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Mikrosekunden bleiben in PostgreSQL erhalten; lokale Zeitgleichstände dürfen nicht umsortieren. */
@Component
public class MessageTimestamp {

    private final Clock clock;
    private Instant previous;

    /** Im Betrieb stammt die Zeit von der UTC-Systemuhr. */
    public MessageTimestamp() {
        clock = Clock.systemUTC();
    }

    /** Eine übergebene Uhr macht Gleichstände und Rücksprünge im Test reproduzierbar. */
    MessageTimestamp(Clock clock) {
        this.clock = clock;
    }

    /** Liefert innerhalb dieser Instanz auch bei gleicher oder rückläufiger Uhr eine spätere Zeit. */
    public synchronized Instant next() {
        Instant current = clock.instant();
        current = current.truncatedTo(ChronoUnit.MICROS);
        if (previous != null && !current.isAfter(previous)) {
            current = previous.plus(1, ChronoUnit.MICROS);
        }
        previous = current;
        return current;
    }
}
