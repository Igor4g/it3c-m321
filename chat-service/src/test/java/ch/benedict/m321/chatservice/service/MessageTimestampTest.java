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
        var instantResult = clock.instant();
        var whenResult = when(instantResult);
        whenResult.thenReturn(now, now, earlier);
        MessageTimestamp timestamp = new MessageTimestamp(clock);

        Instant first = timestamp.next();
        Instant second = timestamp.next();
        Instant third = timestamp.next();

        var timestamp2 = Instant.parse("2026-11-13T10:00:00.123456Z");
        assertEquals(timestamp2, first);
        var isAfterResult = second.isAfter(first);
        assertTrue(isAfterResult);
        var isAfterResult2 = third.isAfter(second);
        assertTrue(isAfterResult2);
        var getNanoResult = third.getNano();
        assertEquals(0, getNanoResult % 1000);
    }

    /** Eine normal weiterlaufende Uhr behält ihren echten späteren Zeitpunkt. */
    @Test
    void keepsLaterSystemTime() {
        Clock clock = mock(Clock.class);
        Instant firstTime = Instant.parse("2026-11-13T10:00:00Z");
        Instant later = firstTime.plusSeconds(1);
        var instantResult = clock.instant();
        var whenResult = when(instantResult);
        whenResult.thenReturn(firstTime, later);
        MessageTimestamp timestamp = new MessageTimestamp(clock);
        var nextResult = timestamp.next();
        assertEquals(firstTime, nextResult);
        var nextResult2 = timestamp.next();
        assertEquals(later, nextResult2);
    }
}
