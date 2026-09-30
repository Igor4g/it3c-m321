# Umsetzungsplan: batch-writer

**M321 · Bewertung 1 · erstellt am 29.09.2026 vor der Implementierung**

Grundlage: [Spezifikation](spec-batch-writer.md), zuerst festgehalten in Commit
`057e2fe`. Der Lernende hat bestätigt, dass die Lehrperson die Weiterarbeit
aufgetragen hat. Eine formelle Freigabe der Spezifikation ist nicht vorgeschrieben;
wir behaupten weder ein bereits erfolgtes Vorzeigen noch eine Lehrerrückmeldung.

## Arbeitsweise

Ein nummerierter Schritt bildet ein fachlich zusammengehöriges Thema mit
zugehörigen Tests und einem eigenen Commit. Erst den passenden Test formulieren,
dann das Verhalten implementieren und prüfen. Rot/Grün nur protokollieren,
wenn der jeweilige Lauf tatsächlich stattgefunden hat.

Alle neuen Namen und Logs auf Englisch; Kommentare, Javadoc und Dokumentation
auf Deutsch. Keine Streams, keine unnötigen Interfaces und keine leeren
Architekturschichten auf Vorrat. Temporäre Arbeitsunterlagen bleiben in tempContext.

Vor jedem Commit: passende Tests, `git diff --check`, Änderungen lesen und nur
die zugehörigen Dateien stagen. Ein durch spätere Schritte veränderter Plan wird
ehrlich weitergeführt, nicht rückwirkend an eine erfundene Reihenfolge angepasst.

Die Umsetzung bleibt schrittweise. Für den Lernenden genügen zunächst kurze
Erklärungen zu Zweck und Datenfluss. Die vertiefte Vorbereitung auf das
Code-Review erfolgt in den zwei Wochen nach der Projektabgabe.

## 1. Maven-Modul und nicht-webbasierter Start

**Warum zuerst:** Der neue Dienst braucht eine getrennte, testbare Laufzeit,
bevor Queue oder Datenbank hinzukommen.

Dateien:
- `pom.xml`: batch-writer in die Modulliste aufnehmen.
- `batch-writer/pom.xml`: Eltern-POM, Boot-Starter und Test-Starter,
  Boot-Maven-Plugin zum Bauen einer ausführbaren JAR.
- `batch-writer/src/main/java/ch/benedict/m321/batchwriter/BatchWriterApplication.java`.
- `batch-writer/src/main/resources/application.yml`: Anwendungsname,
  `spring.main.web-application-type: none`.
- `batch-writer/src/test/java/ch/benedict/m321/batchwriter/BatchWriterApplicationTest.java`.
- `chat-service/Dockerfile`: auch das neue Modul-POM kopieren, damit
  Maven die gesamte Eltern-Modulliste weiterhin auflösen kann.

Test und Nachweis:
1. Vorhandene Lehrertests als Ausgangspunkt mit `mvn clean test` prüfen.
2. Starttest vor der Hauptklasse anlegen; fehlenden Startpunkt nachweisen.
3. Hauptklasse und Konfiguration ergänzen.
4. `mvn -pl batch-writer -am test`: Spring-Kontext startet ohne Webserver.
5. `mvn clean test`: auch die Lehrertests bleiben grün.
6. Docker-Build des chat-service prüfen, weil sein Build-Kontext verändert wurde.

Zielcommit: `chore: Eigenständiges Grundgerüst für den Batch-Writer anlegen`.
Bezug: Grundlage für S1 und S2. Noch keine Persistenz behaupten.

- [x] Schritt umgesetzt und geprüft (29.09.2026).

## 2. JSON-Vertrag ohne Java-Typheader

**Warum jetzt:** Falsche oder nicht lesbare Daten dürfen später nicht eine
ganze Schreibtransaktion blockieren.

Dateien unter der Paketwurzel `ch.benedict.m321.batchwriter`:
- `dto/ChatMessage.java`: eigener record mit den sechs Vertragsfeldern.
- `messaging/MessageReader.java`: JSON lesen und validieren.
- Bei Bedarf eine einfache `InvalidMessageException` für klar unterscheidbare
  Datenfehler; keine abstrakte Fehlerhierarchie.
- Passende Tests unter `src/test/java/.../messaging/`.

Verhalten: Content-Type prüfen; bekannte Felder strikt lesen; unbekannte Felder
ignorieren; UUID, Instant und nicht leere Texte prüfen; keine Abhängigkeit auf
chat-service-Klassen oder __TypeId__. U+0000 und ungültigen Unicode erkennen,
bevor JDBC aufgerufen wird.

Tests: vollständiges JSON nur mit content_type; zusätzlich fremder __TypeId__;
Umlaute/Zeilenumbruch; unbekanntes Feld; fehlender Wert, falscher Typ, ungültige
UUID/Zeit und ungültiges JSON. IDs/Zeitpunkt werden nicht ersetzt.

Befehl: `mvn -pl batch-writer -am test`.
Zielcommit: `feat: Nachrichtenvertrag im Batch-Writer prüfen`.
Bezug: S5 und S8.

- [x] Schritt umgesetzt und geprüft (29.09.2026).

## 3. Transaktionale und idempotente Datenbankablage

**Warum vor dem Consumer:** Zuerst muss feststehen, wann ein Batch wirklich
dauerhaft gespeichert ist. Daran hängt später die Bestätigung an RabbitMQ.

Dateien:
- Modul-POM: JDBC, PostgreSQL-Treiber, Lombok für Konstruktor-Injektion
  und PostgreSQL-Testcontainers ergänzen.
- `src/main/resources/schema.sql`: Tabelle message und Raum/Zeit-Index.
- `repository/MessageRepository.java`: parametrisierter Batch-INSERT mit
  `ON CONFLICT (id) DO NOTHING`.
- `service/BatchWriteService.java`: genau eine Transaktionsgrenze pro
  nicht leerem Batch; Rückkehr erst nach erfolgreichem COMMIT.
  `TransactionTemplate` hält diese Grenze ausdrücklich im Service sichtbar;
  leere Batches kehren vor Beginn einer Transaktion zurück.
- DB-Konfiguration gemäss Spezifikation.
- Repository-/Service-Integrationstests mit echtem PostgreSQL und derselben schema.sql.

Tests: mehrere Nachrichten und alle Felder; doppelte ID innerhalb/eines
weiteren Batches; bestehender Inhalt bleibt unverändert; Fehler beim Schreiben
führt zum Rollback des gesamten Batches. Mikrosekundenpräzision beachten.
Der Starttest bekommt die nun tatsächlich benötigte Testdatenbank, statt
produktive Auto-Konfiguration nur für einen grünen Test abzuschalten.

Befehl: `mvn -pl batch-writer -am test`.
Zielcommit: `feat: Nachrichten stapelweise und idempotent speichern`.
Bezug: S3, S4 und S5.

- [x] Schritt umgesetzt und geprüft (29.09.2026).

## 4. Queue-Consumer mit Bestätigung nach COMMIT

**Warum jetzt:** JSON-Vertrag und DB-Schreibweg sind unabhängig geprüft;
nun werden sie zu einem vollständigen Verarbeitungspfad verbunden.

Dateien:
- Modul-POM: AMQP und RabbitMQ-Testcontainers; Lombok ist seit Schritt 3 vorhanden.
- `config/RabbitConfig.java`: kompatible Queues und Batch-Listener-Konfiguration.
- `config/BatchProperties.java`: positive Batch-/Zeitwerte, nur tatsächlich
  verwendete Einstellungen.
- `messaging/MessageConsumer.java`: lesen, speichern, dann einzeln ACK senden.
- Integrationstests mit echtem RabbitMQ und PostgreSQL; Testkonfiguration für
  Verbindungseinstellungen der zufälligen Containerports.

Tests: vollständiger Batch, Restbatch und einzelne Nachricht werden gespeichert;
S5 direkt mit zwei identischen AMQP-Bodies und nur content_type.
Keine DLQ bei Duplikaten. Ungültige Nachricht einzeln in die DLQ;
gültige Nachbarn werden gespeichert. Test zusätzlich mit Publisher-Typheader.
Ein gezielter Ablauf-Test prüft, dass ACK erst nach Rückkehr des Schreibservices
erfolgt; echte Container belegen den vollständigen Pfad.

Befehl: `mvn -pl batch-writer -am test`.
Zielcommit: `feat: Queue-Nachrichten erst nach dem Speichern bestätigen`.
Bezug: S3, S5 und S8.

- [x] Schritt umgesetzt und geprüft (29.09.2026).

## 5. Datenbankausfall und automatische Erholung

**Warum separat:** Der Normalfall darf die Ausfallbehandlung nicht verdecken.
Jetzt ergänzen wir die klar begrenzte Verantwortung für Wiederzustellung.

Änderungen:
- DB-/Transaktionsfehler vom JSON-Datenfehler unterscheiden.
- Bei technischem DB-Fehler Pause und NACK mit requeue, kein ACK und keine DLQ
  für gültige Nachrichten; keine feste maximale Versuchszahl.
- Kanalfehler weiterreichen; alte delivery-tags nicht auf neuem Kanal verwenden.
- Unterbrechung beim Beenden berücksichtigen; Fehler und Erholung verständlich loggen.

Tests:
- Echten PostgreSQL-Container im laufenden Test für 15 Sekunden stoppen und
  denselben Container wieder starten. Docker-Stop/Start mit ausdrücklich gebundenem,
  frei gewähltem Testport verwenden: eine automatische Docker-Portvergabe kann sich
  beim Wiederstart ändern. Kein neuer Container mit anderer URL.
- 300 Nachrichten während des Ausfalls senden, keine frühe Bestätigung.
- Nach Wiederkehr alle IDs in DB, Queue leer, DLQ unverändert;
  Spring-Kontext/Writer läuft ohne Neustart weiter, höchstens 90 Sekunden.
- DB-Wiederstart und Containerbereinigung in Fehlerpfaden sicherstellen.
- Wiederzustellung nach bereits erfolgtem Commit bleibt idempotent.

Befehl: `mvn -pl batch-writer -am test`.
Zielcommit: `fix: Nachrichten bei Datenbankausfall erhalten und erneut verarbeiten`.
Bezug: S7 sowie Zustellgarantie.

- [x] Schritt umgesetzt und geprüft (29.09.2026).

## 6. Reproduzierbarer Compose-Stack

**Warum erst jetzt:** Der bereits getestete Dienst wird zusammen mit seiner
Infrastruktur startbar gemacht.

Dateien:
- `batch-writer/Dockerfile`: mehrstufiger Java-21-Build ohne Webserver.
- `docker-compose.yml`: postgres, batch-writer, Healthchecks, Schema-Mount,
  benannte Volumes und stabiler Broker-Hostname; kein Host-Port.
- `.env.example`: alle in der Spezifikation genannten Variablen.
- `.dockerignore`: lokale Unterlagen, Zugangsdaten und Build-Ergebnisse ausschliessen.
- `README.md`: tatsächlichen Stand und Start-/Testbefehle aktualisieren.

Prüfung: `mvn clean test`, `docker compose config --quiet`,
`docker compose up -d --build`. Tabelle/Index automatisch vorhanden;
alle vier Dienste laufen intern. Eine Nachricht über den unveränderten
POST /messages bis in PostgreSQL nachweisen. Der Test prüft damit auch das
tatsächliche JSON des bereitgestellten Publishers.
Keine Geheimnisse oder lokale .env committen.

Zielcommit: `feat: Batch-Writer und PostgreSQL in Compose integrieren`.
Bezug: S2 und S3.

- [x] Schritt umgesetzt und geprüft (29.09.2026).

## 7. Rückstau, Transaktionszahl und zwei Instanzen nachweisen

**Warum nach dem Compose-Start:** Einzeltests belegen keine Instanzskalierung
und keine gemessene Transaktionszahl des ausgelieferten Stacks.

Befehle und Auswertung aus der Spezifikation verwenden:
- Writer stoppen, 1000 Nachrichten ansammeln, wieder starten.
- Alle IDs vorhanden, Queue leer; höchstens 100 tatsächliche DB-Transaktionen.
- `docker compose up -d --scale batch-writer=2`.
- Zwei Consumer an derselben Queue; weitere 1000 IDs ohne zusätzliche Zeilen.
- S7 auch nach der Skalierung ausführen; beide Writer-Prozesse bleiben bestehen.

Bei Abweichungen zuerst die Ursache bestimmen. Nur die notwendige Änderung
vornehmen und den betroffenen Test wiederholen. Abnahmekommandos dürfen
korrigiert werden, aber Kriterien und fehlgeschlagene Messungen nicht verstecken.

Nachweise in `docs/abnahme-batch-writer.md`: Datum, geprüfter Stand,
Befehl, Ist-Wert, Soll-Wert, Ergebnis. Keine ausgedachten Messwerte.
Zielcommit: `test: Batch-Verarbeitung und Skalierung nachweisen`.
Bezug: S4, S6 und S7.

- [x] Schritt umgesetzt und geprüft (29.09.2026).

## 8. Vollständige Abnahme und Abgabestand

**Warum zum Schluss:** Ein sauberer Arbeitsrechner beweist noch keinen
reproduzierbaren Fork. Die Abgabe muss ohne lokale Hilfsdateien funktionieren.

1. `mvn clean test` im Projektstamm in einem Lauf, mit allen Integrationstests.
2. Frischen Klon/Checkout des vorgesehenen Standes verwenden; .env nur aus
   .env.example kopieren. Keinen bestehenden Stack gleichzeitig in chat-net betreiben.
3. S2–S8 in der geforderten Reihenfolge auf demselben Stack, ohne Aufräumen.
4. Jede neue Klasse und Methode auf Kommentare und Verständlichkeit prüfen;
   keine Streams, keine versehentlich getrackten Secrets/tempContext-Dateien.
5. Spezifikation, Plan, README und reale Implementierung abgleichen.
   Offene Punkte und Prüfstatus wahrheitsgemäss dokumentieren.

Zielcommit: `docs: Geprüften Abgabestand dokumentieren`.
Nach dem letzten relevanten Fix betroffene Tests und notwendige Gesamtabnahme
wiederholen. Danach main im eigenen Fork veröffentlichen, den final geprüften
Commit mit bewertung-1 markieren und den Tag pushen; Fork-Link einreichen.
Keine automatische Nachricht an die Lehrperson versenden.

Termin für das fertige Projekt: **02.10.2026, 23:59 Uhr**.
Das spätere Code-Review ist ein eigener Termin.

- [x] Schritt umgesetzt und geprüft (29.09.2026).

## Prüfprotokoll

Hier stehen ausschliesslich tatsächlich ausgeführte Prüfungen. Weitere Einträge
werden zusammen mit dem jeweiligen Umsetzungsschritt ergänzt.

- 29.09.2026, vor Umsetzung: Maven 3.9.12 aus vorhandenem lokalem Wrapper-Cache
  gefunden; Java 21 verwendet. `mvn validate` für den Ausgangsstand erfolgreich.
- Docker Desktop für die folgenden Containerprüfungen gestartet; Bereitschaft
  und Integrationstests sind damit noch nicht nachgewiesen.

### Schritt 1 – tatsächliche Ergebnisse vom 29.09.2026

- Ausgangsstand: mvn clean test erfolgreich, 14 Lehrertests ohne Fehler/Überspringen.
- Rot: neuer Starttest ohne Anwendungsklasse ausgeführt; erwarteter Fehler
  wegen fehlender @SpringBootConfiguration, 1 Test mit 1 Fehler.
- Grün: Anwendungsklasse und application.yml ergänzt; Modul-Starttest bestanden.
- Danach mvn clean test vom Projektstamm: 14 Tests im chat-service und 1 Test
  im batch-writer; keine Fehler und keine übersprungenen Tests.
- Docker-Build des chat-service mit erweiterter Eltern-Modulliste erfolgreich:
  docker build -f chat-service/Dockerfile -t it3c-m321-chat-service:step-1 .
- Die Tests wurden mit Java 21 und dem vorhandenen Maven 3.9.12 ausgeführt.
  Maven liegt lokal im Wrapper-Cache und wurde über seinen vollständigen Pfad gestartet.
- Noch nicht umgesetzt: JSON-Verarbeitung, Queue-Consumer und Datenbankablage.
### Schritt 2 – tatsächliche Ergebnisse vom 29.09.2026

- Zuerst Vertragstests und Jackson-Abhängigkeit ergänzt: erwarteter roter Lauf
  durch fehlende Klassen ChatMessage, MessageReader und InvalidMessageException
  bei der Testkompilierung; noch keine ausgeführten Testfälle in diesem Lauf.
- Danach eigenen record und expliziten JSON-Leser implementiert.
  Der Leser erhält nur body und content_type; keine Java-Typheader.
- Fehlende/falsch typisierte Werte, Format-/UTF-8-Fehler, UUIDs, Zeitpunkte,
  nicht speicherbare Zeichen und unveränderte Textübernahme geprüft.
- Nach Ergänzung der Tests für nachfolgendes JSON und führende/abschliessende
  Leerzeichen: mvn clean test vom Projektstamm erfolgreich.
- 14 Lehrertests und 64 Writer-Testfälle (63 Vertragstests, 1 Starttest),
  insgesamt 78; keine Fehler und keine übersprungenen Tests.
- Die Tests verwenden parametrisierte Fälle für die sechs Pflichtfelder;
  es sind keine 63 unterschiedlichen Testmethoden.
- Die echte AMQP-Verarbeitung mit fremdem __TypeId__, Duplikaten und Datenbank
  bleibt wie vorgesehen Teil von Schritt 4. S5 ist noch nicht vollständig bestanden.

### Schritt 3 – tatsächliche Ergebnisse vom 29.09.2026

- Zuerst Integrationstests und benötigte Abhängigkeiten ergänzt: erwarteter
  Fehler bei der Testkompilierung wegen der noch fehlenden Klasse BatchWriteService.
- Danach schema.sql, MessageRepository und BatchWriteService ergänzt.
  JDBC-Batch und TransactionTemplate speichern jede nicht leere Lieferung
  innerhalb einer Transaktion. Leere Lieferungen öffnen keine Transaktion.
- Der Starttest und die Schreibtests verwenden einen von Spring verwalteten
  PostgreSQL-16-Container mit derselben schema.sql wie der spätere Compose-Stack.
  Es gibt keine umschliessende Testtransaktion, die das COMMIT verdecken könnte.
- Fünf neue Integrationstests prüfen alle Felder mit Mikrosekundenpräzision,
  doppelte IDs innerhalb und zwischen Lieferungen, Erhalt des ursprünglichen
  Inhalts, vollständigen Rollback bei einer NOT-NULL-Verletzung samt anschliessender
  erfolgreicher Speicherung sowie eine leere Lieferung.
- mvn clean test vom Projektstamm erfolgreich: 14 Lehrertests und 69 Writer-Tests,
  insgesamt 83; keine Fehler und keine übersprungenen Tests.
- Lombok für die Konstruktor-Injektion wird bereits hier benötigt; der Plan
  wurde entsprechend angepasst. Keine zusätzlichen Interfaces oder JPA.
- Noch ausstehend: AMQP-Consumer, tatsächlicher DB-Ausfall mit Wiederzustellung,
  Compose-Einbindung und vollständige Szenarienabnahme S3–S7.

### Schritt 4 – tatsächliche Ergebnisse vom 29.09.2026

- Zuerst Ablauf-Tests ergänzt: erwarteter Fehler bei der Testkompilierung,
  weil MessageConsumer noch nicht existierte.
- RabbitConfig deklariert chat.persist und chat.dlq mit denselben Argumenten
  wie der chat-service. Der Listener erhält rohe AMQP-Nachrichten ohne
  automatische Umwandlung anhand fremder Java-Klassennamen.
- Spring sammelt bis zu 100 Nachrichten mit 200 ms Batch-Zeitgrenze und
  höchstens 50 ms Empfangswartezeit. Eine Instanz hat genau einen Consumer
  und prefetch=100. Beide Batch-Einstellungen müssen positiv sein.
- MessageConsumer prüft einzeln, verwirft nur ungültige Lieferungen in die DLQ,
  speichert alle gültigen Nachrichten zusammen und sendet erst danach einzelne ACKs.
  Ein rein ungültiger Batch ruft den Schreibservice nicht auf.
- Fünf Ablauf-Tests prüfen ACK nach Service-Rückkehr, einzelne NACKs,
  keine Speicherung rein ungültiger Daten, kein ACK bei DB-Fehler und
  Weitergabe von Kanalfehlern ohne weitere Bestätigungen.
- Sechs Tests mit echten RabbitMQ-/PostgreSQL-Containern prüfen Einzelnachricht,
  Duplikate ohne Typheader, fremden Publisher-Typheader, 100er-Batch plus 3er-Rest,
  fehlerhafte Nachricht zwischen gültigen Nachbarn und rein ungültigen Batch.
  Nach dem Stop des Consumers bleibt die Queue leer; unbestätigte Nachrichten
  würden beim Schliessen des Kanals wieder in der Queue erscheinen.
- Modulprüfung erfolgreich: 80 Writer-Tests, keine Fehler oder übersprungenen Tests.
- Anschliessend mvn clean test vom Projektstamm: 14 Lehrertests und 80 Writer-Tests,
  insgesamt 94; keine Fehler und keine übersprungenen Tests.
- Noch nicht fertig: gezielte Pause und Wiederzustellung bei DB-Ausfall.
  Der Speicherfehler wird derzeit weitergereicht und niemals mit ACK bestätigt.
  Die vollständige Wiederherstellung nach echtem DB-Stop gehört zu Schritt 5.
- S5 ist auf Integrationstest-Ebene nachgewiesen; die Abnahme auf dem fertigen
  Compose-Stack zusammen mit den übrigen Szenarien steht weiterhin aus.

### Schritt 5 – tatsächliche Ergebnisse vom 29.09.2026

- Neue Fehlerfalltests zuerst ergänzt; erwarteter Fehler bei der Testkompilierung,
  weil der Consumer die Retry-Einstellung noch nicht als Konstruktorparameter erhielt.
- Der Consumer fängt nur Datenzugriffs- und Transaktionsfehler beim Speichern ab.
  Nach 1000 ms Pause folgen einzelne NACKs mit requeue=true für gültige Nachrichten.
  Es gibt keine feste maximale Versuchszahl. Ungültige Nachrichten bleiben getrennt.
- Ein Interrupt während der Pause bleibt gesetzt und wird weitergereicht;
  der Consumer sendet in diesem Fall weder ACK noch NACK. Kanalfehler werden
  nicht als Datenbankfehler behandelt und stoppen die weitere Bestätigung.
- Fehlerlogs nennen Batchgrösse, Fehlerklasse und Retry-Pause, aber keine
  SQL-Fehlermeldung mit möglichen Chat-Inhalten. Erfolgreiche Batches werden geloggt.
- Erster Containerlauf schlug fehl: Docker vergab beim Stop/Start einen anderen
  zufälligen Host-Port. Eine separate Wegwerf-Containerprüfung bestätigte das
  Verhalten (32768 vor Stop, 32769 nach Start). Dieser Probecontainer wurde entfernt.
- Die Testkonfiguration bindet deshalb einen zuvor frei gewählten Port ausdrücklich.
  Das betrifft nur Tests. Container und Daten bleiben bei der Unterbrechung gleich;
  Writer-Verbindung und Spring-Kontext werden nicht neu konfiguriert oder gestartet.
- Die Prüfverbindung wartet während des PostgreSQL-Starts auf Erreichbarkeit.
  Der Ausfalltest ist in kurze Hilfsmethoden für Senden, Ausfall und Erholung gegliedert.
- Erfolgreicher Modulprüflauf: 85 Tests, keine Fehler oder übersprungenen Tests.
  300 Nachrichten nach 15 Sekunden DB-Ausfall in 16545 ms ab Beginn des Stopps
  vollständig gespeichert. Während des Ausfalls waren alle 300 im Broker vorhanden;
  anschliessend stimmen alle IDs, Queue und DLQ sind leer. Kein Writer-Neustart.
- Zusätzlich geprüft: erneute Zustellung nach einem früheren COMMIT erzeugt
  keine zweite Zeile; COMMIT-Fehler und Kanalfehler beim NACK; Interrupt-Erhalt.
- Abschliessend mvn clean test vom Projektstamm: 14 Lehrertests und 85 Writer-Tests,
  insgesamt 99, ohne Fehler oder übersprungene Tests. Im erneuten Ausfalltest
  wurden alle 300 Nachrichten in 17130 ms ab Beginn des Stopps gespeichert.
- S7 ist auf Integrationstest-Ebene nachgewiesen. Die Abnahme im Compose-Stack
  und nach Skalierung auf zwei Writer bleibt Teil der Schritte 6 bis 8.

### Schritt 6 – tatsächliche Ergebnisse vom 29.09.2026

- Mehrstufiges Java-21-Dockerfile für den Writer ergänzt. Beide Builds lesen
  die Eltern-Modulliste; die Images enthalten die ausführbaren Anwendungen.
- Compose startet rabbitmq, chat-service, postgres und batch-writer im Netz chat-net.
  PostgreSQL und RabbitMQ besitzen benannte Datenvolumes; RabbitMQ hat einen
  stabilen Hostnamen. Es gibt keine veröffentlichten Host-Ports und keine
  automatische Neustartregel, die einen Writer-Fehler verdecken könnte.
- .env.example ergänzt und daraus lokal .env erstellt. .env und tempContext
  bleiben unversioniert; .dockerignore schliesst sie zusätzlich vom Build-Kontext aus.
- docker compose config --quiet und docker compose up -d --build erfolgreich.
  Tabelle message, Primärschlüssel und Index message_room_sent_at_idx wurden
  beim ersten Start mit leerem PostgreSQL-Volume automatisch angelegt.
- Beim ersten Start meldete der frühere RabbitMQ-ping-Healthcheck zu früh Bereitschaft.
  Der Writer verband sich erst nach automatischen Consumer-Wiederholungen.
  Der Healthcheck prüft deshalb jetzt ausdrücklich den aktiven AMQP-Listener 5672.
  Ein erneuter Stack-Start zeigte direkt eine erfolgreiche Verbindung.
- mvn clean test: 14 Lehrertests und 85 Writer-Tests, insgesamt 99,
  keine Fehler und keine übersprungenen Tests. Ausfalltest: 300 Nachrichten
  in 16366 ms ab Beginn des DB-Stopps wiederhergestellt.
- HTTP-Test aus einem temporären curl-Container im Netz chat-net: POST /messages
  lieferte 202 und ID 853c1542-0236-4fd9-98db-b4cde7e70aff. Dieselbe ID und der
  Inhalt Hallo aus Compose waren anschliessend in PostgreSQL vorhanden.
- Nach docker compose down (ohne -v) und erneutem up -d --build war die Zeile
  weiterhin vorhanden. Eine weitere HTTP-Nachricht wurde ebenfalls gespeichert
  (ID 3fd76c59-17eb-4f77-a152-2a9c44bda725, Inhalt Hallo nach Neustart).
- chat.persist und chat.dlq hatten danach jeweils 0 bereite und 0 unbestätigte
  Nachrichten. Docker-Inspektion bestätigte leere PortBindings bei allen vier Diensten.
- README enthält die tatsächlich ausgeführten Start-, HTTP- und SQL-Befehle sowie
  Hinweise zur Erhaltung der Daten. Der lokale Stack bleibt gestartet.
- Noch ausstehend: 1000er-Last, Transaktionsmessung, zwei Writer und vollständige
  Abnahme auf einem frischen Klon. S2/S3 sind damit noch nicht vollständig abgenommen.

### Schritt 7 – tatsächliche Ergebnisse vom 29.09.2026

- Geprüfter Anwendungsstand: 36eb6ae. Keine Änderungen am Anwendungscode nötig.
- S3: 1000 IDs gespeichert, Queue leer; 7,70 s Sendezeit und 1,40 s bis zum Nachweis.
- S4: Rückstau mit 1000 ready, 0 unacknowledged und 0 Consumern;
  Verarbeitung samt Nachweis nach Writer-Start in 6,22 s. Vollständige DB-Statistik
  stieg von 300 auf 320: 20 Transaktionen insgesamt bei höchstens 100 erlaubt.
- S5: identischer JSON-Body zweimal ohne Java-Typheader; eine Zeile,
  alle sechs Felder unverändert, keine DLQ-Nachricht.
- S6: zwei Consumer an chat.persist; weitere 1000 IDs gespeichert, beide Writer
  verarbeiteten je 500 Nachrichten. Sendezeit 6,35 s, Nachweis danach in 1,34 s.
- S7: DB 15 Sekunden gestoppt; alle 300 Nachrichten innerhalb des Ausfalls
  versendet und im Broker nachgewiesen (100 ready, 200 unacknowledged).
  Alle IDs nach 21,12 s ab DB-Stopp gespeichert, Queue/DLQ leer und beide
  Writer-Prozesse unverändert. Erlaubt sind höchstens 90 s.
- Drei Messprobleme korrigiert und im Abnahmebericht offengelegt: CRLF im
  Linux-Prüfskript, noch unvollständige PostgreSQL-Statistik und als Job-Fehler
  übernommener Docker-Fortschritt auf stderr. Die betroffenen Prüfungen wurden wiederholt.
- Befehle in der Spezifikation angepasst; ausführliche Ergebnisse und Grenzen
  in docs/abnahme-batch-writer.md. Keine Daten zwischen den Szenarien gelöscht.
- Der lokale Stack bleibt mit zwei Writern gestartet. Frischer Klon,
  vollständige Abfolge S1–S8, Schlussreview und Abgabe bleiben Schritt 8.

### Schritt 8 – tatsächliche Ergebnisse vom 29.09.2026

- Alle sechs Seiten des Lehrerauftrags erneut mit Projekt und Abgaben abgeglichen.
  Das Lehrer-Repository steht weiterhin auf f8ea557; keine zusätzlichen Ausgangsdateien.
- Frischen lokalen Git-Klon von 4b55c19 erstellt; ausschliesslich versionierte Dateien,
  eigene .env nur aus .env.example. Vorherigen Compose-Stack ohne Volume-Löschung
  beendet; der Prüfstack startete mit neuen leeren Volumes und derselben Konfiguration.
- S1: mvn clean test im Klon, 99 Tests ohne Fehler oder übersprungene Tests.
- S2: vier Dienste laufen, Schema und Index automatisch vorhanden,
  PortBindings bei allen Diensten leer. Kein manuelles SQL-Setup.
- S3–S7: dokumentierte Blöcke auf demselben Prüfstack in Reihenfolge ausgeführt,
  ohne Datenbereinigung. 3301 Zeilen am Ende und beide Queues vollständig leer.
  S4: 20 Transaktionen; S7: alle 300 IDs in 27,69 s einschliesslich zusätzlicher
  Schlussprüfungen, beide Writer-Prozesse unverändert.
- S8: keine Streams; Klassen-/Methodenkommentare und Verantwortlichkeiten gelesen;
  englische Namen/Logs, deutsche Erklärungen, keine getrackte .env oder tempContext.
  Git-Historie belegt Spezifikation und Plan vor der Implementierung.
- Die Abschlussänderungen betreffen nur Dokumentation. Der bereits geprüfte
  Anwendungscode, die Tests, Dockerfiles und Compose-Konfiguration bleiben gleich.
- README um eine kurze Klassenübersicht ergänzt; Spezifikation und Abnahmebericht
  auf den tatsächlich abgeschlossenen Prüfstand gebracht.
- Prüfstack anschliessend beendet, ursprünglichen Arbeitsstack mit erhaltenen
  Daten und zwei Writern wieder gestartet. Keine Volumes gelöscht.
- Veröffentlichung: main und bewertung-1 kennzeichnen den abschliessend dokumentierten
  Stand im eigenen Fork. Den Fork-Link reicht der Lernende selbst im Abgabeportal ein;
  es wird keine Nachricht an die Lehrperson versendet.

## Nachprüfung und Korrekturen vom 30.09.2026

Der zusätzliche Audit nach Schritt 8 hat einen fehlenden Speicherbereichstest
für Zeitpunkte und kleine Stil-/Kommentarlücken gezeigt. Die ursprünglichen
Schritte und Messungen bleiben als Verlauf erhalten.

1. **Zeitbereich absichern:** Zuerst Reader-Regressionen, dann Bereichsprüfung;
   echte Queue-/DB-Tests für ungültige Zeitpunkte neben gültigen Nachrichten sowie
   beide erlaubten Grenzen. So wird der nachgewiesene dauerhafte Retry verhindert.
2. **Lesbarkeit bereinigen:** Verschachtelte Berechnungen in benannte Schritte
   aufteilen, drei veraltete Kommentare korrigieren; anschliessend Root-Testlauf.
3. **Abgabeunterlagen abgleichen:** Alle versionierten Dateien prüfen, den
   Gesamtentwurf als solchen kennzeichnen, README und Abnahme aktualisieren.
   Erneute Compose-Abnahme S2–S8 auf frischem Klon des korrigierten Codes.

Jedes Thema erhält einen eigenen deutschen Commit. Historische Planungsunterlagen
werden nicht als aktueller Implementierungsstand ausgegeben. Die Frage nach dem
früheren Vorzeigen der Spezifikation wird auf Wunsch des Lernenden nicht weiter bearbeitet.

### Nachprüfung 1 – Zeitbereich

- Reader-Regression vor der Korrektur ausgeführt: 71 Tests, davon sechs erwartete
  Fehler, weil nicht speicherbare Zeitpunkte noch akzeptiert wurden.
- Der erste echte Grenztest zeigte zusätzlich: pgJDBC wandelt Werte vor
  4713 v. Chr. in -infinity um. Deshalb begrenzt der Reader den gemeinsamen
  endlichen Wertebereich von Treiber und Datenbank, nicht nur den Java-Typ.
- Nach Korrektur: 72 Reader-Tests und 13 Consumer-Integrationstests grün.
  Beide gültigen Grenzen wurden über Queue und JDBC unverändert gespeichert;
  drei ungültige Zeitpunkte landeten jeweils allein in der DLQ, ihre gültigen
  Nachbarn in der Tabelle. Der bestehende Ausfalltest blieb ebenfalls grün.
- Befehl: `mvn -pl batch-writer -Dtest=MessageReaderTest,MessageConsumerIntegrationTest test`.
