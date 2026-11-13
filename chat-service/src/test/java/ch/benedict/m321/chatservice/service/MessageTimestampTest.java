package ch.benedict.m321.chatservice.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Verhindert einen Reihenfolgeverlust durch Uhrgleichstände oder JDBC-Zeitpräzision. */
class MessageTimestampTest {

    /** Eine stehenbleibende oder korrigierte Uhr darf keine frühere lokale Nachricht erzeugen. */
    @Test
    void staysOrderedWhenClockRepeatsAndMovesBackwards() {
        Clock clock = mock(Clock.class);
        Instant now = Instant.parse("2026-11-13T10:00:00.123456789Z");
        Instant earlier = now.minusSeconds(10);
        when(clock.instant()).thenReturn(now, now, earlier);
        MessageTimestamp timestamp = new MessageTimestamp(clock);

        Instant first = timestamp.next();
        Instant second = timestamp.next();
        Instant third = timestamp.next();

        assertEquals(Instant.parse("2026-11-13T10:00:00.123456Z"), first);
        assertTrue(second.isAfter(first));
        assertTrue(third.isAfter(second));
        assertEquals(0, third.getNano() % 1000);
    }

    /** Eine normal weiterlaufende Uhr behält ihren echten späteren Zeitpunkt. */
    @Test
    void keepsLaterSystemTime() {
        Clock clock = mock(Clock.class);
        Instant firstTime = Instant.parse("2026-11-13T10:00:00Z");
        Instant later = firstTime.plusSeconds(1);
        when(clock.instant()).thenReturn(firstTime, later);
        MessageTimestamp timestamp = new MessageTimestamp(clock);
        assertEquals(firstTime, timestamp.next());
        assertEquals(later, timestamp.next());
    }
}
