package ch.benedict.m321.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 * Broker und Datenbank dürfen beim Start noch unerreichbar sein. Die wirkliche
 * Zustellung wird separat mit echten Containern geprüft.
 */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ChatServiceApplicationTest {

    /** Der Starttest meldet fehlende Beans, ohne daraus bereits erfolgreiche Zustellung abzuleiten. */
    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
