package ch.benedict.m321.batchwriter;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * Prüft den eigenständigen Start des Writers.
 * Der Dienst verarbeitet später Queue-Nachrichten und benötigt keinen Webserver.
 */
@SpringBootTest
@Import({PostgresTestConfiguration.class, RabbitTestConfiguration.class})
class BatchWriterApplicationTest {

    @Autowired
    private ApplicationContext applicationContext;

    /**
     * Ein normaler Spring-Kontext reicht für einen Hintergrunddienst aus.
     * Ein versehentlich hinzugefügter Web-Kontext soll hier auffallen.
     */
    @Test
    void startsWithoutAWebServer() {
        assertInstanceOf(AnnotationConfigApplicationContext.class, applicationContext);
    }
}
