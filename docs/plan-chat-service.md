# chat-service — ursprünglicher Umsetzungsplan der Vorlage

> Historische Vorlage für den bereits bereitgestellten `chat-service`.
> Offene Kästchen und Codebeispiele bilden den damaligen Bauplan ab, nicht den
> aktuellen Abgabestatus. Sie sind keine erneut auszuführenden Arbeitsaufträge.
> Der aktuelle Quelltext liegt in `chat-service/`; für Bewertung 1 gelten
> [Writer-Plan](plan-batch-writer.md) und [Abnahmebericht](abnahme-batch-writer.md).

**Ziel:** Der `chat-service` nimmt eine Nachricht per `POST /messages` entgegen, vergibt UUID und Server-Zeitstempel und legt sie in beide Wege — Queue `chat.persist` und Fanout-Exchange `chat.delivery`.

**Architektur:** Klassische Schichtung `controller → service → dto`, dazu `config` für alles, was beim Start eingerichtet wird. Der Controller kennt kein RabbitMQ, der Publisher kein HTTP. Der Dienst hat weder Datenbank noch Token-Prüfung: das Gateway ist der einzige Wachposten, und die Persistenz ist Sache des `batch-writer`.

**Tech-Stack:** Java 21, Spring Boot 3.5.16, Spring AMQP, Jakarta Bean Validation, RabbitMQ 3.13, JUnit 5, Testcontainers, Maven Multi-Modul.

**Spec:** [`../PLANUNG.md`](../PLANUNG.md) — insbesondere Abschnitt 3.4 (Nachrichtenfluss), 3.5 (Queues) und Schritt 3 der Umsetzungsreihenfolge.

## Globale Vorgaben

Diese Punkte gelten für **jede** Aufgabe in diesem Plan:

- **Java 21**, Spring Boot **3.5.16** (bei Maven Central geprüft am 04.09.2026).
- **Code auf Englisch** — Klassen, Methoden, Variablen, Dateinamen und **Log-Meldungen**. **Alles andere auf Deutsch** — Kommentare, Javadoc, Commit-Messages, Antworttexte an den Benutzer.
- **Keine verschachtelten Aufrufe.** Ein Ergebnis pro Zeile, in eine benannte Variable. Gilt auch in Tests.
- **Keine Interfaces mit einer einzigen Implementierung**, keine Abstraktion auf Vorrat.
- **Lombok** für Logger (`@Slf4j`) und Konstruktor-Injektion (`@RequiredArgsConstructor`). Für Datenklassen trotzdem Java-`record` — das kann Java selbst, dafür braucht es Lombok nicht.
- **Kein `ports:`-Eintrag** in `docker-compose.yml`. Der einzige offene Port des Gesamtsystems gehört später dem `web-gateway`.
- **Keine Geheimnisse im Repository.** Zugangsdaten kommen aus `.env`, im Repo steht nur `.env.example`.
- **Jeder Commit endet mit dieser Zeile:**
  ```
  Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>
  ```
- **Voraussetzung:** Docker läuft (Testcontainers startet echte RabbitMQ-Container), und die vorhandenen Planungsdateien (`CLAUDE.md`, `PLANUNG.md`, `docs/`, `.gitignore`) sind bereits committet.

---

## Abgrenzung

| Bewusst **nicht** in diesem Plan | Warum |
|---|---|
| Datenbank und Chat-Historie | Kommt in Schritt 4 der Umsetzungsreihenfolge. Ohne Persistenz ist der Weg kürzer und schneller sichtbar |
| Empfänger-Auflösung über `room_member` | Braucht die Datenbank. Solange fächert `chat.delivery` an alle Gateways aus, und das Gateway entscheidet, welcher seiner Clients die Nachricht braucht |
| JWT-Prüfung im `chat-service` | Das Gateway ist der einzige Wachposten (PLANUNG.md, Abschnitt 3.1). Der `chat-service` ist von aussen nicht erreichbar |
| Publisher Confirms | Erst messen, dann härten. Siehe offener Punkt 9 |
| Wiederholversuche und Dead-Letter-Auswertung | Die DLQ wird hier nur **angelegt**. Wer sie füllt und ausliest, ist der `batch-writer` |

---

## Dateistruktur

```
pom.xml                              # Eltern-POM, Modulliste
docker-compose.yml                   # nur rabbitmq + chat-service, KEIN ports:-Eintrag
.env.example                         # Beispielwerte für die Schulung
chat-service/
├── pom.xml
├── Dockerfile
└── src/
    ├── main/java/ch/benedict/m321/chatservice/
    │   ├── ChatServiceApplication.java
    │   ├── config/
    │   │   ├── QueueNames.java                  # die drei Namen an genau einer Stelle
    │   │   └── RabbitConfig.java                # Queue, Exchange, DLQ, JSON-Konverter
    │   ├── controller/
    │   │   ├── MessageController.java           # POST /messages
    │   │   └── MessageExceptionHandler.java     # Broker weg -> 503 statt 500
    │   ├── service/
    │   │   ├── MessageService.java              # die Regeln: UUID, Zeitstempel, weiterreichen
    │   │   └── MessagePublisher.java            # der Weg nach draussen zu RabbitMQ
    │   └── dto/
    │       ├── SendMessageRequest.java          # was hereinkommt
    │       ├── AcceptedResponse.java            # was zurückgeht
    │       └── ChatMessage.java                 # was in die Queue geht
    ├── main/resources/application.yml
    └── test/java/ch/benedict/m321/chatservice/
        ├── ChatServiceApplicationTest.java
        ├── config/RabbitConfigIntegrationTest.java
        ├── controller/MessageControllerIntegrationTest.java
        ├── controller/MessageExceptionHandlerTest.java
        ├── dto/SendMessageRequestTest.java
        ├── service/MessagePublisherIntegrationTest.java
        └── service/MessageServiceTest.java
```

**Wer wen kennt** — und zwar nur in dieser Richtung:

```
MessageController  ──ruft auf──►  MessageService  ──ruft auf──►  MessagePublisher  ──►  RabbitMQ
        │                                │                               │
        └────────── kennt dto ───────────┴───────────────────────────────┘
```

Testklassen mit dem Namensende `...Test` werden von Surefire automatisch ausgeführt. Deshalb heissen auch die Integrationstests `...IntegrationTest` und nicht `...IT` — sonst bräuchte es zusätzlich das Failsafe-Plugin, und das wäre ein Werkzeug mehr, das man erklären muss.

---

## Task 1: Maven-Gerüst und Anwendungsstart

**Dateien:**
- Anlegen: `pom.xml`
- Anlegen: `chat-service/pom.xml`
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/ChatServiceApplication.java`
- Anlegen: `chat-service/src/main/resources/application.yml`
- Test: `chat-service/src/test/java/ch/benedict/m321/chatservice/ChatServiceApplicationTest.java`

**Schnittstellen:**
- Verbraucht: nichts
- Stellt bereit: Paketwurzel `ch.benedict.m321.chatservice`, Artefakt `ch.benedict.m321:chat-service:0.1.0-SNAPSHOT`, lauffähiger Spring-Kontext ohne laufenden Broker

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`chat-service/src/test/java/ch/benedict/m321/chatservice/ChatServiceApplicationTest.java`

```java
package ch.benedict.m321.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 * Ein RabbitMQ wird dafür nicht gebraucht: die Verbindung wird erst
 * beim ersten Senden aufgebaut, nicht beim Start.
 */
@SpringBootTest
class ChatServiceApplicationTest {

    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl chat-service test`
Erwartet: Fehlschlag — es gibt weder ein `pom.xml` noch eine `@SpringBootApplication`-Klasse.

- [ ] **Schritt 3: Eltern-POM anlegen**

`pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <!-- Spring Boot als Eltern-POM: liefert die abgestimmten Versionen
         aller Abhängigkeiten, damit wir sie nicht einzeln pflegen müssen. -->
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.16</version>
        <relativePath/>
    </parent>

    <groupId>ch.benedict.m321</groupId>
    <artifactId>it3c-m321</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <name>M321 Chat-App</name>

    <properties>
        <java.version>21</java.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <!-- Weitere Dienste kommen hier dazu: batch-writer, web-gateway, load-generator. -->
    <modules>
        <module>chat-service</module>
    </modules>
</project>
```

- [ ] **Schritt 4: Modul-POM anlegen**

`chat-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>ch.benedict.m321</groupId>
        <artifactId>it3c-m321</artifactId>
        <version>0.1.0-SNAPSHOT</version>
    </parent>

    <artifactId>chat-service</artifactId>
    <name>chat-service</name>
    <description>Nimmt Nachrichten entgegen und verteilt sie auf Schreib- und Zustellweg</description>

    <dependencies>
        <!-- REST-Schnittstelle -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <!-- RabbitMQ -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-amqp</artifactId>
        </dependency>

        <!-- Prüfung der eingehenden Felder (@NotNull, @NotBlank) -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-validation</artifactId>
        </dependency>

        <!-- Lombok erzeugt Logger und Konstruktoren beim Uebersetzen.
             "optional" heisst: nur wir brauchen es, niemand der uns benutzt. -->
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>

        <!-- Startet im Test einen echten RabbitMQ im Container -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-testcontainers</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>org.testcontainers</groupId>
            <artifactId>rabbitmq</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <configuration>
                    <excludes>
                        <!-- Lombok wird nur zum Uebersetzen gebraucht und
                             gehoert nicht ins ausgelieferte Jar. -->
                        <exclude>
                            <groupId>org.projectlombok</groupId>
                            <artifactId>lombok</artifactId>
                        </exclude>
                    </excludes>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Schritt 5: Hauptklasse anlegen**

`chat-service/src/main/java/ch/benedict/m321/chatservice/ChatServiceApplication.java`

```java
package ch.benedict.m321.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des chat-service.
 *
 * Dieser Dienst ist die einzige Stelle im System, die eine Nachricht vom
 * Benutzer entgegennimmt. Er speichert nichts selbst — er reicht die
 * Nachricht an RabbitMQ weiter.
 */
@SpringBootApplication
public class ChatServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
```

- [ ] **Schritt 6: Konfiguration anlegen**

`chat-service/src/main/resources/application.yml`

```yaml
spring:
  application:
    name: chat-service
  rabbitmq:
    # Im Docker-Netz heisst der Broker "rabbitmq". Beim Start ausserhalb
    # von Docker greift der Vorgabewert "localhost".
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}

server:
  port: 8080

logging:
  level:
    # Im Unterricht wollen wir jeden Schritt sehen.
    ch.benedict.m321: DEBUG
```

- [ ] **Schritt 7: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl chat-service test`
Erwartet: BUILD SUCCESS, `ChatServiceApplicationTest` grün.

- [ ] **Schritt 8: Committen**

```bash
git add pom.xml chat-service/pom.xml chat-service/src
git commit -m "chore: Maven-Elternprojekt und Modul chat-service anlegen" \
  -m "Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>"
```

---

## Task 2: Die drei Datenklassen

**Dateien:**
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/dto/SendMessageRequest.java`
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/dto/AcceptedResponse.java`
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/dto/ChatMessage.java`
- Test: `chat-service/src/test/java/ch/benedict/m321/chatservice/dto/SendMessageRequestTest.java`

**Schnittstellen:**
- Verbraucht: Paketwurzel aus Task 1
- Stellt bereit:
  - `SendMessageRequest(UUID roomId, String senderId, String senderName, String content)`
  - `AcceptedResponse(UUID id, Instant sentAt)`
  - `ChatMessage(UUID id, UUID roomId, String senderId, String senderName, String content, Instant sentAt)`

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`chat-service/src/test/java/ch/benedict/m321/chatservice/dto/SendMessageRequestTest.java`

```java
package ch.benedict.m321.chatservice.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Feldregeln der eingehenden Nachricht.
 * Diese Regeln sind die Grenze des Dienstes: was hier durchkommt,
 * gilt danach als gültig.
 */
class SendMessageRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    @Test
    void acceptsCompleteRequest() {
        SendMessageRequest request = new SendMessageRequest(
                UUID.randomUUID(), "anna", "Anna Muster", "Hallo zusammen");

        Set<ConstraintViolation<SendMessageRequest>> violations = validator.validate(request);

        assertTrue(violations.isEmpty());
    }

    @Test
    void rejectsBlankContent() {
        SendMessageRequest request = new SendMessageRequest(
                UUID.randomUUID(), "anna", "Anna Muster", "   ");

        Set<ConstraintViolation<SendMessageRequest>> violations = validator.validate(request);

        assertEquals(1, violations.size());
    }

    @Test
    void rejectsMissingRoomId() {
        SendMessageRequest request = new SendMessageRequest(
                null, "anna", "Anna Muster", "Hallo");

        Set<ConstraintViolation<SendMessageRequest>> violations = validator.validate(request);

        assertEquals(1, violations.size());
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=SendMessageRequestTest`
Erwartet: Übersetzungsfehler — `SendMessageRequest` gibt es noch nicht.

- [ ] **Schritt 3: `SendMessageRequest` anlegen**

```java
package ch.benedict.m321.chatservice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Was das Gateway an den chat-service schickt.
 *
 * Bewusst OHNE id und ohne Zeitstempel: beides vergibt der Server.
 * Ein Client, der sich seine eigene Nachrichten-ID ausdenken darf,
 * kann fremde Nachrichten überschreiben.
 *
 * @param roomId     der Raum, in den die Nachricht gehört
 * @param senderId   die sub-Kennung des Absenders aus Keycloak
 * @param senderName der Anzeigename, damit die Historie lesbar bleibt
 * @param content    der Text der Nachricht
 */
public record SendMessageRequest(
        @NotNull UUID roomId,
        @NotBlank String senderId,
        @NotBlank String senderName,
        @NotBlank String content) {
}
```

- [ ] **Schritt 4: `AcceptedResponse` anlegen**

```java
package ch.benedict.m321.chatservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Was der chat-service zurückgibt, nachdem er die Nachricht angenommen hat.
 *
 * Der Client braucht die id, um seine eigene Nachricht in der Zustellung
 * wiederzuerkennen, und den Zeitstempel, um sie richtig einzusortieren.
 *
 * @param id     die vom Server vergebene Nachrichten-ID
 * @param sentAt der vom Server gesetzte Zeitpunkt
 */
public record AcceptedResponse(UUID id, Instant sentAt) {
}
```

- [ ] **Schritt 5: `ChatMessage` anlegen**

```java
package ch.benedict.m321.chatservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Die fertige Nachricht, wie sie in beide Queues geht.
 *
 * Diese Form ist der Vertrag zwischen den Diensten — aber der Vertrag ist
 * das JSON, nicht diese Klasse. batch-writer und web-gateway bekommen
 * später ihre eigene Kopie. Ein gemeinsames Modul würde alle Dienste
 * aneinanderbinden, und genau das wollen wir nicht zeigen.
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
```

- [ ] **Schritt 6: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=SendMessageRequestTest`
Erwartet: 3 Tests, alle grün.

- [ ] **Schritt 7: Committen**

```bash
git add chat-service/src/main/java/ch/benedict/m321/chatservice/dto \
        chat-service/src/test/java/ch/benedict/m321/chatservice/dto
git commit -m "feat: Datenklassen für Ein- und Ausgang des chat-service" \
  -m "Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>"
```

---

## Task 3: RabbitMQ-Konfiguration

**Dateien:**
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/config/QueueNames.java`
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/config/RabbitConfig.java`
- Test: `chat-service/src/test/java/ch/benedict/m321/chatservice/config/RabbitConfigIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: Paketwurzel aus Task 1
- Stellt bereit:
  - `QueueNames.PERSIST_QUEUE` = `"chat.persist"`
  - `QueueNames.DELIVERY_EXCHANGE` = `"chat.delivery"`
  - `QueueNames.DEAD_LETTER_QUEUE` = `"chat.dlq"`
  - Beans: `Queue persistQueue`, `Queue deadLetterQueue`, `FanoutExchange deliveryExchange`, `MessageConverter jsonMessageConverter`

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`chat-service/src/test/java/ch/benedict/m321/chatservice/config/RabbitConfigIntegrationTest.java`

```java
package ch.benedict.m321.chatservice.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft gegen einen ECHTEN RabbitMQ, dass unsere Queues beim Start
 * tatsächlich angelegt werden. Ein Mock würde hier nichts beweisen:
 * die Deklaration passiert im Broker, nicht in unserem Code.
 */
@SpringBootTest
@Testcontainers
class RabbitConfigIntegrationTest {

    /**
     * @ServiceConnection setzt spring.rabbitmq.host und -port automatisch
     * auf den gestarteten Container. Wir müssen nichts konfigurieren.
     */
    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Test
    void declaresPersistQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.PERSIST_QUEUE);

        assertNotNull(properties);
    }

    @Test
    void declaresDeadLetterQueue() {
        Properties properties = rabbitAdmin.getQueueProperties(QueueNames.DEAD_LETTER_QUEUE);

        assertNotNull(properties);
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=RabbitConfigIntegrationTest`
Erwartet: Übersetzungsfehler — `QueueNames` gibt es noch nicht.

- [ ] **Schritt 3: `QueueNames` anlegen**

```java
package ch.benedict.m321.chatservice.config;

/**
 * Die Namen der Queues und des Exchange an genau EINER Stelle.
 *
 * Sie werden an drei Orten gebraucht — beim Anlegen, beim Senden und im
 * Test. Ein Tippfehler in einem String wäre sonst erst zur Laufzeit
 * sichtbar, und zwar als "die Nachricht kommt nie an".
 */
public final class QueueNames {

    /** Schreibweg: hier holt der batch-writer die Nachrichten ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Zustellweg: fanout an alle web-gateway-Instanzen. */
    public static final String DELIVERY_EXCHANGE = "chat.delivery";

    /** Dead Letter: was der batch-writer endgültig nicht verarbeiten kann. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
```

- [ ] **Schritt 4: `RabbitConfig` anlegen**

```java
package ch.benedict.m321.chatservice.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
public class RabbitConfig {

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede web-gateway-Instanz jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer WebSocket-Clients
     * im betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
```

- [ ] **Schritt 5: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=RabbitConfigIntegrationTest`
Erwartet: 2 Tests grün. Der erste Lauf dauert länger, weil das Image `rabbitmq:3.13-management` geladen wird.

- [ ] **Schritt 6: Committen**

```bash
git add chat-service/src/main/java/ch/benedict/m321/chatservice/config \
        chat-service/src/test/java/ch/benedict/m321/chatservice/config
git commit -m "feat: Queues, Fanout-Exchange und Dead-Letter-Queue anlegen" \
  -m "Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>"
```

---

## Task 4: MessagePublisher

**Dateien:**
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessagePublisher.java`
- Test: `chat-service/src/test/java/ch/benedict/m321/chatservice/service/MessagePublisherIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: `QueueNames` (Task 3), `ChatMessage` (Task 2)
- Stellt bereit: `MessagePublisher.publish(ChatMessage message)` — `void`, Konstruktor `MessagePublisher(RabbitTemplate rabbitTemplate)`

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`chat-service/src/test/java/ch/benedict/m321/chatservice/service/MessagePublisherIntegrationTest.java`

```java
package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Der wichtigste Test dieses Dienstes: geht die Nachricht wirklich in
 * BEIDE Wege? Genau das ist der Kern der Entkopplung von Zustellung
 * und Speicherung.
 */
@SpringBootTest
@Testcontainers
class MessagePublisherIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MessagePublisher messagePublisher;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    /**
     * Eine Queue ist gemeinsamer Zustand: sie überlebt die einzelne
     * Testmethode. Ohne dieses Leeren liest der zweite Test die
     * Nachricht des ersten und schlägt scheinbar grundlos fehl.
     */
    @BeforeEach
    void emptyPersistQueue() {
        rabbitAdmin.purgeQueue(QueueNames.PERSIST_QUEUE);
    }

    @Test
    void publishesToPersistQueue() {
        ChatMessage message = createMessage("Hallo Schreibweg");

        messagePublisher.publish(message);

        ChatMessage persisted = receiveFrom(QueueNames.PERSIST_QUEUE);
        assertNotNull(persisted);
        assertEquals(message.id(), persisted.id());
        assertEquals("Hallo Schreibweg", persisted.content());
    }

    @Test
    void publishesToDeliveryExchange() {
        // Wir spielen ein web-gateway: eigene Queue an den Fanout binden.
        Queue gatewayQueue = new AnonymousQueue();
        rabbitAdmin.declareQueue(gatewayQueue);

        FanoutExchange exchange = new FanoutExchange(QueueNames.DELIVERY_EXCHANGE);
        Binding binding = BindingBuilder.bind(gatewayQueue).to(exchange);
        rabbitAdmin.declareBinding(binding);

        ChatMessage message = createMessage("Hallo Zustellweg");

        messagePublisher.publish(message);

        String queueName = gatewayQueue.getName();
        ChatMessage delivered = receiveFrom(queueName);
        assertNotNull(delivered);
        assertEquals(message.id(), delivered.id());
        assertEquals("Hallo Zustellweg", delivered.content());
    }

    /**
     * Holt eine Nachricht aus einer Queue.
     *
     * Der Zieltyp wird hier ausdrücklich mitgegeben. Grund: in der Queue
     * liegt JSON, nicht ein Java-Objekt. Wer liest, muss wissen, was er
     * erwartet — genau so wird es später auch der batch-writer machen,
     * der ja seine eigene Kopie der Klasse hat.
     */
    private ChatMessage receiveFrom(String queueName) {
        ParameterizedTypeReference<ChatMessage> targetType = new ParameterizedTypeReference<>() {
        };
        return rabbitTemplate.receiveAndConvert(queueName, 5000, targetType);
    }

    /** Baut eine vollständige Nachricht, damit die Tests kurz bleiben. */
    private ChatMessage createMessage(String content) {
        UUID messageId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", content, sentAt);
    }
}
```

> **Zwei Fallen, die beim Bauen aufgeschlagen sind** (04.09.2026, hier bereits eingearbeitet):
> 1. `Jackson2JsonMessageConverter` deserialisiert nur Klassen aus `java.util` und `java.lang`.
>    Der Empfänger muss den Zieltyp selbst mitgeben — was ohnehin richtig ist, weil in der Queue
>    JSON liegt und keine Java-Klasse.
> 2. Eine Queue überlebt die einzelne Testmethode. Beide Tests schreiben nach `chat.persist`,
>    also muss sie vor jedem Test geleert werden — sonst liest der zweite Test die Nachricht
>    des ersten.

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=MessagePublisherIntegrationTest`
Erwartet: Übersetzungsfehler — `MessagePublisher` gibt es noch nicht.

- [ ] **Schritt 3: `MessagePublisher` anlegen**

```java
package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

/**
 * Der Weg nach draussen zu RabbitMQ.
 *
 * Jede Nachricht geht in ZWEI Richtungen, und zwar bewusst getrennt:
 * der Zustellweg soll nicht darauf warten, dass jemand die Nachricht
 * in die Datenbank geschrieben hat.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessagePublisher {

    /** Leerer Routing-Key: ein Fanout-Exchange ignoriert ihn ohnehin. */
    private static final String FANOUT_ROUTING_KEY = "";

    private final RabbitTemplate rabbitTemplate;

    /**
     * Legt die Nachricht in den Schreibweg und in den Zustellweg.
     *
     * Reihenfolge mit Absicht: erst persist, dann delivery. Schlägt das
     * Senden fehl, ist die Nachricht dann noch nirgends zugestellt worden
     * und der Benutzer bekommt einen ehrlichen Fehler.
     */
    public void publish(ChatMessage message) {
        rabbitTemplate.convertAndSend(QueueNames.PERSIST_QUEUE, message);
        rabbitTemplate.convertAndSend(QueueNames.DELIVERY_EXCHANGE, FANOUT_ROUTING_KEY, message);

        log.info("Message {} published to queue {} and exchange {}",
                message.id(), QueueNames.PERSIST_QUEUE, QueueNames.DELIVERY_EXCHANGE);
    }
}
```

- [ ] **Schritt 4: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=MessagePublisherIntegrationTest`
Erwartet: 2 Tests grün.

- [ ] **Schritt 5: Committen**

```bash
git add chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessagePublisher.java \
        chat-service/src/test/java/ch/benedict/m321/chatservice/service
git commit -m "feat: Nachricht in Schreibweg und Zustellweg veroeffentlichen" \
  -m "Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>"
```

---

## Task 5: MessageService

**Dateien:**
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessageService.java`
- Test: `chat-service/src/test/java/ch/benedict/m321/chatservice/service/MessageServiceTest.java`

**Schnittstellen:**
- Verbraucht: `MessagePublisher.publish(ChatMessage)` (Task 4), `SendMessageRequest` / `AcceptedResponse` / `ChatMessage` (Task 2)
- Stellt bereit: `MessageService.accept(SendMessageRequest request)` → `AcceptedResponse`, Konstruktor `MessageService(MessagePublisher messagePublisher)`

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`chat-service/src/test/java/ch/benedict/m321/chatservice/service/MessageServiceTest.java`

```java
package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Prüft die Regeln des Dienstes ohne Broker: wird eine ID vergeben,
 * wird ein Zeitstempel gesetzt, geht genau eine Nachricht raus.
 */
class MessageServiceTest {

    /**
     * Ein Test-Doppel: es merkt sich, was veröffentlicht wurde, statt
     * wirklich zu senden. Bewusst von Hand geschrieben statt mit einem
     * Mock-Framework — so sieht man beim Lesen, was passiert.
     */
    private static class RecordingPublisher extends MessagePublisher {

        private ChatMessage published;
        private int publishCount;

        RecordingPublisher() {
            // Das Doppel benutzt das RabbitTemplate nie, deshalb null.
            super(null);
        }

        @Override
        public void publish(ChatMessage message) {
            this.published = message;
            this.publishCount = this.publishCount + 1;
        }
    }

    @Test
    void assignsIdAndTimestamp() {
        RecordingPublisher publisher = new RecordingPublisher();
        MessageService messageService = new MessageService(publisher);
        UUID roomId = UUID.randomUUID();
        SendMessageRequest request =
                new SendMessageRequest(roomId, "anna", "Anna Muster", "Hallo");

        AcceptedResponse response = messageService.accept(request);

        assertNotNull(response.id());
        assertNotNull(response.sentAt());
    }

    @Test
    void publishesExactlyOnceWithTheSameId() {
        RecordingPublisher publisher = new RecordingPublisher();
        MessageService messageService = new MessageService(publisher);
        UUID roomId = UUID.randomUUID();
        SendMessageRequest request =
                new SendMessageRequest(roomId, "anna", "Anna Muster", "Hallo");

        AcceptedResponse response = messageService.accept(request);

        assertEquals(1, publisher.publishCount);
        assertEquals(response.id(), publisher.published.id());
        assertEquals(roomId, publisher.published.roomId());
        assertEquals("Anna Muster", publisher.published.senderName());
        assertEquals("Hallo", publisher.published.content());
    }

    @Test
    void assignsADifferentIdEveryTime() {
        RecordingPublisher publisher = new RecordingPublisher();
        MessageService messageService = new MessageService(publisher);
        SendMessageRequest request =
                new SendMessageRequest(UUID.randomUUID(), "anna", "Anna Muster", "Hallo");

        AcceptedResponse first = messageService.accept(request);
        AcceptedResponse second = messageService.accept(request);

        assertEquals(2, publisher.publishCount);
        assertNotEquals(first.id(), second.id());
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=MessageServiceTest`
Erwartet: Übersetzungsfehler — `MessageService` gibt es noch nicht.

- [ ] **Schritt 3: `MessageService` anlegen**

```java
package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Die Regeln des chat-service.
 *
 * Hier — und nur hier — bekommt eine Nachricht ihre Identität und ihre
 * Zeit. Beides vergibt der Server, damit alle Empfänger dieselbe
 * Reihenfolge und dieselbe ID sehen.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MessageService {

    private final MessagePublisher messagePublisher;

    /**
     * Nimmt eine Nachricht an und gibt zurück, unter welcher ID sie im
     * System unterwegs ist.
     *
     * Der Rückgabewert bedeutet ausdrücklich NICHT "gespeichert" — die
     * Nachricht liegt zu diesem Zeitpunkt erst in der Queue.
     */
    public AcceptedResponse accept(SendMessageRequest request) {
        int contentLength = request.content().length();
        log.info("Message received for room {} with {} characters", request.roomId(), contentLength);

        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();

        ChatMessage message = new ChatMessage(
                messageId,
                request.roomId(),
                request.senderId(),
                request.senderName(),
                request.content(),
                sentAt);

        messagePublisher.publish(message);

        return new AcceptedResponse(messageId, sentAt);
    }
}
```

- [ ] **Schritt 4: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=MessageServiceTest`
Erwartet: 3 Tests grün.

- [ ] **Schritt 5: Committen**

```bash
git add chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessageService.java \
        chat-service/src/test/java/ch/benedict/m321/chatservice/service/MessageServiceTest.java
git commit -m "feat: Nachrichten annehmen, ID und Zeitstempel vergeben" \
  -m "Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>"
```

---

## Task 6: MessageController

**Dateien:**
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/controller/MessageController.java`
- Test: `chat-service/src/test/java/ch/benedict/m321/chatservice/controller/MessageControllerIntegrationTest.java`

**Schnittstellen:**
- Verbraucht: `MessageService.accept(SendMessageRequest)` (Task 5)
- Stellt bereit: `POST /messages` → `202 Accepted` mit `AcceptedResponse`; ungültige Eingabe → `400 Bad Request`

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`chat-service/src/test/java/ch/benedict/m321/chatservice/controller/MessageControllerIntegrationTest.java`

```java
package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Der Weg von aussen nach innen, einmal ganz durch: HTTP rein,
 * Nachricht in der Queue raus.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class MessageControllerIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void acceptsMessageAndPutsItOnTheQueue() throws Exception {
        String requestBody = """
                {
                  "roomId": "3f2b1c4e-0000-0000-0000-000000000001",
                  "senderId": "anna",
                  "senderName": "Anna Muster",
                  "content": "Hallo zusammen"
                }
                """;

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sentAt").exists());

        ParameterizedTypeReference<ChatMessage> targetType = new ParameterizedTypeReference<>() {
        };
        ChatMessage message = rabbitTemplate.receiveAndConvert(
                QueueNames.PERSIST_QUEUE, 5000, targetType);

        assertNotNull(message);
        assertEquals("Hallo zusammen", message.content());
        assertEquals("Anna Muster", message.senderName());
    }

    @Test
    void rejectsEmptyContent() throws Exception {
        String requestBody = """
                {
                  "roomId": "3f2b1c4e-0000-0000-0000-000000000001",
                  "senderId": "anna",
                  "senderName": "Anna Muster",
                  "content": "   "
                }
                """;

        mockMvc.perform(post("/messages")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=MessageControllerIntegrationTest`
Erwartet: Fehlschlag mit `404` — die Route `/messages` gibt es noch nicht.

- [ ] **Schritt 3: `MessageController` anlegen**

```java
package ch.benedict.m321.chatservice.controller;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import ch.benedict.m321.chatservice.service.MessageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Die interne REST-Schnittstelle des chat-service.
 *
 * Erreichbar ist sie nur aus dem Docker-Netz — das web-gateway und der
 * load-generator rufen sie auf. Deshalb prüft dieser Dienst kein Token:
 * das hat das Gateway bereits getan.
 */
@RestController
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /**
     * Nimmt eine Nachricht entgegen.
     *
     * Antwort ist 202 und nicht 201, weil die Nachricht angenommen, aber
     * noch nirgends gespeichert ist. Der Statuscode sagt genau das aus,
     * was das System tut.
     */
    @PostMapping("/messages")
    public ResponseEntity<AcceptedResponse> send(@Valid @RequestBody SendMessageRequest request) {
        AcceptedResponse response = messageService.accept(request);
        return ResponseEntity.accepted().body(response);
    }
}
```

- [ ] **Schritt 4: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=MessageControllerIntegrationTest`
Erwartet: 2 Tests grün.

- [ ] **Schritt 5: Committen**

```bash
git add chat-service/src/main/java/ch/benedict/m321/chatservice/controller \
        chat-service/src/test/java/ch/benedict/m321/chatservice/controller
git commit -m "feat: REST-Schnittstelle POST /messages" \
  -m "Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>"
```

---

## Task 7: Ehrlicher Fehler, wenn der Broker weg ist

**Dateien:**
- Anlegen: `chat-service/src/main/java/ch/benedict/m321/chatservice/controller/MessageExceptionHandler.java`
- Test: `chat-service/src/test/java/ch/benedict/m321/chatservice/controller/MessageExceptionHandlerTest.java`

**Schnittstellen:**
- Verbraucht: nichts aus früheren Tasks
- Stellt bereit: `MessageExceptionHandler.handleBrokerNotAvailable(AmqpException exception)` → `ResponseEntity<String>` mit Status `503`

Hintergrund: offener Punkt 9 in PLANUNG.md. Es gibt keine Eingangs-Queue, die einen Ausfall abfängt. Fällt der Broker aus, muss der Benutzer das **sehen** — eine still verschluckte Nachricht ist das schlechteste mögliche Verhalten.

- [ ] **Schritt 1: Den fehlschlagenden Test schreiben**

`chat-service/src/test/java/ch/benedict/m321/chatservice/controller/MessageExceptionHandlerTest.java`

```java
package ch.benedict.m321.chatservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Ist RabbitMQ nicht erreichbar, darf der Benutzer keine 500 sehen und
 * schon gar kein stilles "ok". 503 heisst: nimm es nochmal, später.
 */
class MessageExceptionHandlerTest {

    @Test
    void answersWithServiceUnavailable() {
        MessageExceptionHandler handler = new MessageExceptionHandler();
        AmqpException exception = new AmqpException("Broker nicht erreichbar");

        ResponseEntity<String> response = handler.handleBrokerNotAvailable(exception);

        assertEquals(503, response.getStatusCode().value());
        assertNotNull(response.getBody());
    }
}
```

- [ ] **Schritt 2: Test laufen lassen und Fehlschlag bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=MessageExceptionHandlerTest`
Erwartet: Übersetzungsfehler — `MessageExceptionHandler` gibt es noch nicht.

- [ ] **Schritt 3: `MessageExceptionHandler` anlegen**

```java
package ch.benedict.m321.chatservice.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Übersetzt Broker-Fehler in eine ehrliche HTTP-Antwort.
 *
 * Ohne diese Klasse würde Spring eine 500 zurückgeben — "unser Fehler,
 * keine Ahnung". 503 sagt dem Aufrufer, dass es sich lohnt, es später
 * erneut zu versuchen.
 */
@RestControllerAdvice
@Slf4j
public class MessageExceptionHandler {

    @ExceptionHandler(AmqpException.class)
    public ResponseEntity<String> handleBrokerNotAvailable(AmqpException exception) {
        log.error("Broker not reachable, message rejected", exception);

        String body = "Nachricht nicht gesendet: der Broker ist nicht erreichbar.";
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}
```

- [ ] **Schritt 4: Test laufen lassen und grün bestätigen**

Ausführen: `mvn -q -pl chat-service test -Dtest=MessageExceptionHandlerTest`
Erwartet: 1 Test grün.

- [ ] **Schritt 5: Committen**

```bash
git add chat-service/src/main/java/ch/benedict/m321/chatservice/controller/MessageExceptionHandler.java \
        chat-service/src/test/java/ch/benedict/m321/chatservice/controller/MessageExceptionHandlerTest.java
git commit -m "feat: 503 statt 500, wenn RabbitMQ nicht erreichbar ist" \
  -m "Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>"
```

---

## Task 8: Docker und docker-compose

**Dateien:**
- Anlegen: `chat-service/Dockerfile`
- Anlegen: `docker-compose.yml`
- Anlegen: `.env.example`
- Anlegen (lokal, **nicht** committen): `.env`

**Schnittstellen:**
- Verbraucht: das lauffähige Modul aus Task 1–7
- Stellt bereit: Docker-Netz `chat-net`, Dienst `chat-service` erreichbar unter `http://chat-service:8080` **nur innerhalb** dieses Netzes

- [ ] **Schritt 1: `Dockerfile` anlegen**

`chat-service/Dockerfile`

```dockerfile
# Stufe 1: bauen
# Der Build-Kontext ist das Projekt-Wurzelverzeichnis, weil das Modul
# das Eltern-POM braucht.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY chat-service/pom.xml chat-service/pom.xml
COPY chat-service/src chat-service/src
# Tests werden hier uebersprungen: Testcontainers braeuchte einen Docker-Daemon
# INNERHALB des Builds. Getestet wird vorher mit "mvn test".
RUN mvn -q -pl chat-service -am package -DskipTests

# Stufe 2: laufen
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/chat-service/target/chat-service-0.1.0-SNAPSHOT.jar app.jar
# EXPOSE dokumentiert den Port nur. Veroeffentlicht wird er NICHT —
# siehe docker-compose.yml, dort steht bewusst kein ports:-Eintrag.
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Schritt 2: `.env.example` anlegen**

```bash
# Beispielwerte für den Unterricht. Die echte .env steht in .gitignore.
RABBITMQ_USER=chat
RABBITMQ_PASSWORD=bitte-lokal-aendern
```

- [ ] **Schritt 3: Lokale `.env` erzeugen**

```bash
cp .env.example .env
git check-ignore -v .env
```

Erwartet: `git check-ignore` bestätigt, dass `.env` ignoriert wird.

- [ ] **Schritt 4: `docker-compose.yml` anlegen**

```yaml
# Ausbaustufe 1: nur der Broker und der chat-service.
# Postgres, Keycloak, web-gateway und batch-writer kommen in den
# folgenden Schritten dazu.
services:

  rabbitmq:
    image: rabbitmq:3.13-management
    environment:
      RABBITMQ_DEFAULT_USER: ${RABBITMQ_USER}
      RABBITMQ_DEFAULT_PASS: ${RABBITMQ_PASSWORD}
    networks:
      - chat-net
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 5s
      timeout: 5s
      retries: 12

  chat-service:
    build:
      context: .
      dockerfile: chat-service/Dockerfile
    environment:
      RABBITMQ_HOST: rabbitmq
      RABBITMQ_USER: ${RABBITMQ_USER}
      RABBITMQ_PASSWORD: ${RABBITMQ_PASSWORD}
    depends_on:
      rabbitmq:
        condition: service_healthy
    networks:
      - chat-net

# KEIN ports:-Eintrag in dieser Datei. Der einzige offene Port des
# Gesamtsystems gehört später dem web-gateway.
networks:
  chat-net:
    name: chat-net
```

- [ ] **Schritt 5: Stack starten**

```bash
docker compose up -d --build
docker compose ps
```

Erwartet: beide Dienste `running`, `rabbitmq` zusätzlich `healthy`, und in der Spalte `PORTS` steht bei **keinem** Dienst ein Eintrag der Form `0.0.0.0:...->...`.

- [ ] **Schritt 6: Von innen eine Nachricht schicken**

```bash
docker run --rm --network chat-net curlimages/curl -s -i -X POST \
  http://chat-service:8080/messages \
  -H 'Content-Type: application/json' \
  -d '{"roomId":"3f2b1c4e-0000-0000-0000-000000000001",
       "senderId":"anna",
       "senderName":"Anna Muster",
       "content":"Hallo"}'
```

Erwartet: `HTTP/1.1 202` und ein JSON-Körper mit `id` und `sentAt`.

- [ ] **Schritt 7: Nachsehen, ob die Nachricht in der Queue liegt**

```bash
docker compose exec rabbitmq rabbitmqctl list_queues name messages
```

Erwartet:
- `chat.persist` steht auf `1` — niemand konsumiert diese Queue bisher, das ist richtig so
- `chat.dlq` steht auf `0`
- `chat.delivery` taucht **nicht** auf, weil es ein Exchange ist und keine Queue. Die Nachricht auf dem Zustellweg ist verworfen worden, weil noch keine Gateway-Queue daran gebunden ist — auch das ist richtig so und ein guter Moment, um Fanout im Unterricht zu erklären.

- [ ] **Schritt 8: Aufräumen und committen**

```bash
docker compose down
git add chat-service/Dockerfile docker-compose.yml .env.example
git commit -m "chore: chat-service und RabbitMQ in docker-compose abbilden" \
  -m "Co-Authored-By: Claude Opus 5 <claude@pritz-it.com>"
```

---

## Abschluss-Prüfung

- [ ] `mvn -q clean test` — alle Tests grün, in einem Lauf
- [ ] `grep -n "ports:" docker-compose.yml` — keine Treffer
- [ ] `git status --short` — sauber, `.env` taucht nicht auf
- [ ] Ein zweiter `curl`-Aufruf erhöht `chat.persist` auf `2` — der Weg ist wiederholbar und nicht zufällig grün gewesen

**Damit ist Schritt 3 der Umsetzungsreihenfolge zur Hälfte erreicht:** die Nachricht läuft vom Aufrufer bis in beide Queues. Was noch fehlt, ist das `web-gateway`, das sie am anderen Ende wieder herausholt und per WebSocket zustellt.
