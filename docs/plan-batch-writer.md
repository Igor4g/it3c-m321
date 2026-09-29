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

- [ ] Schritt umgesetzt und geprüft.

## 3. Transaktionale und idempotente Datenbankablage

**Warum vor dem Consumer:** Zuerst muss feststehen, wann ein Batch wirklich
dauerhaft gespeichert ist. Daran hängt später die Bestätigung an RabbitMQ.

Dateien:
- Modul-POM: JDBC, PostgreSQL-Treiber und PostgreSQL-Testcontainers ergänzen.
- `src/main/resources/schema.sql`: Tabelle message und Raum/Zeit-Index.
- `repository/MessageRepository.java`: parametrisierter Batch-INSERT mit
  `ON CONFLICT (id) DO NOTHING`.
- `service/BatchWriteService.java`: genau eine Transaktionsgrenze pro
  nicht leerem Batch; Rückkehr erst nach erfolgreichem COMMIT.
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

- [ ] Schritt umgesetzt und geprüft.

## 4. Queue-Consumer mit Bestätigung nach COMMIT

**Warum jetzt:** JSON-Vertrag und DB-Schreibweg sind unabhängig geprüft;
nun werden sie zu einem vollständigen Verarbeitungspfad verbunden.

Dateien:
- Modul-POM: AMQP, benötigtes Lombok und RabbitMQ-Testcontainers.
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

- [ ] Schritt umgesetzt und geprüft.

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
  denselben Container wieder starten. Docker-Stop/Start verwenden, damit
  Identität und Portbindung erhalten bleiben; kein neuer Container mit anderer URL.
- 300 Nachrichten während des Ausfalls senden, keine frühe Bestätigung.
- Nach Wiederkehr alle IDs in DB, Queue leer, DLQ unverändert;
  Spring-Kontext/Writer läuft ohne Neustart weiter, höchstens 90 Sekunden.
- DB-Wiederstart und Containerbereinigung in Fehlerpfaden sicherstellen.
- Wiederzustellung nach bereits erfolgtem Commit bleibt idempotent.

Befehl: `mvn -pl batch-writer -am test`.
Zielcommit: `fix: Nachrichten bei Datenbankausfall erhalten und erneut verarbeiten`.
Bezug: S7 sowie Zustellgarantie.

- [ ] Schritt umgesetzt und geprüft.

## 6. Reproduzierbarer Compose-Stack

**Warum erst jetzt:** Der bereits getestete Dienst wird zusammen mit seiner
Infrastruktur startbar gemacht.

Dateien:
- `batch-writer/Dockerfile`: mehrstufiger Java-21-Build ohne Webserver.
- `docker-compose.yml`: postgres, batch-writer, Healthchecks, Schema-Mount,
  benannte Volumes und stabiler Broker-Hostname; kein Host-Port.
- `.env.example`: alle in der Spezifikation genannten Variablen.
- `README.md`: tatsächlichen Stand und Start-/Testbefehle aktualisieren.

Prüfung: `mvn clean test`, `docker compose config --quiet`,
`docker compose up -d --build`. Tabelle/Index automatisch vorhanden;
alle vier Dienste laufen intern. Eine Nachricht über den unveränderten
POST /messages bis in PostgreSQL nachweisen. Der Test prüft damit auch das
tatsächliche JSON des bereitgestellten Publishers.
Keine Geheimnisse oder lokale .env committen.

Zielcommit: `feat: Batch-Writer und PostgreSQL in Compose integrieren`.
Bezug: S2 und S3.

- [ ] Schritt umgesetzt und geprüft.

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

- [ ] Schritt umgesetzt und geprüft.

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

- [ ] Schritt umgesetzt und geprüft.

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