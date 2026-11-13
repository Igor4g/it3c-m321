# M321 — Chat-App: Persistenz und Live-Zustellung

Lernprojekt zum Modul **M321 Verteilte Systeme programmieren**, Klasse IT3c.
Bewertung 1 ergänzt den Batch-Writer; Bewertung 2 ergänzt die raumbezogene
Live-Zustellung und Wiederaufnahme im bestehenden Chat-Service.

## Stand

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Java 21, Spring Boot 3 | POST, eigene Fanout-Queue, SSE je Raum und Wiederaufnahme | implementiert; 137 Gesamttests und Compose S2–S8 grün |
| rabbitmq | RabbitMQ 3.13 | Dauerhafte Persistenz-Queue und eigene Live-Kopie je Chat-Instanz | in Compose eingebunden |
| batch-writer | Java 21, Spring Boot 3 | Einziger SQL-Schreiber, ACK nach COMMIT | Bewertung 1 abgeschlossen |
| postgres | PostgreSQL 16 | Dauerhafter Verlauf; Chat-Service liest, Writer schreibt | Schema und Datenvolume vorhanden |

Die eigene Compose-Schlussabnahme für Bewertung 2 wird getrennt dokumentiert.
Login/Keycloak, Gateway, Oberfläche, Raumverwaltung sowie Gelesen- und
Geliefert-Status gehören nicht zu dieser Abgabe. Das spätere allgemeine Lastziel
von 100'000 Nachrichten pro Minute wird nicht als erreicht behauptet.

## Start und Tests

Voraussetzungen: **JDK 21**, **Maven 3.9**, **Git**, laufender **Docker mit Compose v2**.
Docker wird auch für die echten RabbitMQ-/PostgreSQL-Integrationstests benötigt.

```bash
git clone https://github.com/Igor4g/it3c-m321.git
cd it3c-m321
cp .env.example .env
mvn clean test
docker compose config --quiet
docker compose up -d --build
docker compose ps
```

In PowerShell lautet der Kopierbefehl `Copy-Item .env.example .env`.
Die Beispielwerte reichen für den lokalen Schulstack. Eigene Zugangsdaten gehören
nur in die ignorierte `.env`. Auch `tempContext/` und Build-Artefakte bleiben lokal.

Kein Dienst veröffentlicht Host-Ports. Alle Dienste sprechen über `chat-net`.
HTTP-Prüfclients laufen ebenfalls in diesem Netz. Zwei Chat-Instanzen starten mit:

```bash
docker compose up -d --scale chat-service=2
```

`mvn clean test` prüft beide Module in einem Lauf: Writer-Fehlerfälle,
echte HTTP-/RabbitMQ-Verteilung an zwei Instanzen und Wiederaufnahme mit PostgreSQL.
Der eigene Gesamtlauf vom 13.11.2026 umfasst 137 erfolgreiche Tests ohne Auslassungen.
`CodeStyleTest` prüft Zweckkommentare an Klassen/Methoden und das Java-Stream-Verbot;
deutsche Sprache und Verständlichkeit werden zusätzlich manuell geprüft.
Die Maven-Testcontainer verwenden eigene, vorübergehende Ports. Das ändert die
portfreie Produktionskonfiguration in Compose nicht.

Die zusätzliche Compose-Abnahme läuft unter Windows aus einem frischen Stack mit
anfangs einer Chat-Instanz. Nach dem Maven-Gesamtlauf:

```powershell
powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario All
```

`All` führt S2–S8 ohne Datenreset dazwischen aus. Das Skript stoppt und pausiert
Dienste; es ist für einen eigenen Prüfstack gedacht. Es löscht keine Datenvolumes.
[Anleitung und Messverfahren](chat-service/src/test/acceptance/README.md) beschreiben
Voraussetzungen, einzelne Szenarien und die ignorierten Ergebnisdateien.

## Nachrichtenweg

`POST /messages` nimmt JSON mit `roomId` (UUID), `senderId`, `senderName` und
`content` entgegen. Der Server ergänzt `id` und `sentAt`. HTTP 202 mit diesen beiden
Werten bedeutet Annahme, noch keine bestätigte Speicherung oder Client-Anzeige.

Der Publisher sendet zuerst an `chat.persist`, danach an den Fanout-Exchange
`chat.delivery`. Die beiden Veröffentlichungen sind keine atomare Transaktion.
Der Writer speichert unabhängig von der Live-Zustellung. Standardwerte:
100 Nachrichten pro Batch, 200 ms Sammelzeit, 1000 ms Pause nach einem technischen
DB-Fehler. Technische Wiederholungen sind nicht zahlenmässig begrenzt.

Jede Chat-Instanz besitzt eine eigene temporäre Queue am Fanout: maximal 999
wartende plus eine unbestätigte Nachricht, ein Consumer und `prefetch=1`.
Bei Überlauf verwirft RabbitMQ alte Live-Kopien (`drop-head`). `chat.persist`
und `chat.dlq` sind davon nicht betroffen. Nach Verbindungsende wird die temporäre
Queue entfernt. Der Consumer bestätigt nach lokaler Übernahme, nicht nach Anzeige
beim Client.

`GET /stream?roomId=<UUID>` öffnet `text/event-stream`. Ein Ereignis enthält die
Nachrichten-UUID als SSE-`id` und vollständiges JSON mit `id`, `roomId`,
`senderId`, `senderName`, `content`, `sentAt` als `data`. Nur Clients desselben
Raums erhalten es. Anfangskommentar und Heartbeats alle 15 Sekunden enthalten
keine Chat-ID oder Chat-Daten.

Ohne `Last-Event-ID` beginnt die Verbindung live ohne automatische alte Historie.
Mit Header liefert der Dienst Nachrichten nach dieser ID nach und wechselt
anschliessend über denselben Ausgabeweg zu Live. PostgreSQL und jüngste RAM-Daten
werden anhand der ID zusammengeführt; der Cursor selbst wird nicht erneut gesendet.
Der jüngste Puffer schliesst die Verzögerung des Writers. Während des DB-Ladens
werden neue Ereignisse bereits für die Verbindung gesammelt.

Nachlieferung ist nach `sentAt`, bei Gleichstand nach kanonischer UUID sortiert.
Live bleibt pro Verbindung FIFO; innerhalb einer Instanz werden Annahme und
Publikation geordnet. Eine globale Reihenfolge konkurrierender Instanzen oder
unbegrenztes Exactly-once wird nicht versprochen.

## Grenzen und Fehler

`LIVE_HISTORY_SIZE`, `LIVE_CLIENT_BUFFER_SIZE` und `LIVE_REPLAY_LIMIT` sind jeweils
standardmässig 10'000 Nachrichten. Sie begrenzen unterschiedliche Anwendungspuffer;
die RabbitMQ-Grenze bleibt unabhängig davon 999 plus eins.
Eine langsame Verbindung hat ihren eigenen Ausgabe-Worker und Puffer. Bei Überlauf
wird diese Verbindung beendet, ohne andere Clients aufzuhalten.

- HTTP 400: fehlender/ungültiger Raum oder ungültige `Last-Event-ID`.
- HTTP 404: Cursor nach erfolgreicher DB-Abfrage unbekannt oder in anderem Raum.
- HTTP 409: Nachlieferung oder Vorbereitung überschreitet die konfigurierte Grenze.
- HTTP 503: DB bei Wiederaufnahme nicht erreichbar oder Live-Consumer nicht bereit.

Reine Live-Verbindungen und POSTs benötigen keine DB. Wiederaufnahme mit Header
braucht eine erfolgreiche DB-Abfrage, auch wenn RAM-Daten vorhanden sind.
Nach Beginn des SSE-Streams kann kein neuer HTTP-Fehlerstatus folgen:
bei Ausgabe- oder Pufferfehler wird die betroffene Verbindung geschlossen.
Fehlende Nachrichten ausserhalb aller verfügbaren Quellen sind nicht allgemein
rekonstruierbar. Genauer Vertrag und Begründungen stehen in der Spezifikation.

## Manuelle Nutzung aus PowerShell

```powershell
$body = '{"roomId":"22222222-2222-4222-8222-222222222222","senderId":"anna","senderName":"Anna Muster","content":"Hallo"}'
$body | docker run --rm -i --network chat-net curlimages/curl:8.10.1 --silent --show-error --fail-with-body -H 'Content-Type: application/json' --data-binary '@-' http://chat-service:8080/messages
docker run --rm --network chat-net curlimages/curl:8.10.1 --no-buffer --fail 'http://chat-service:8080/stream?roomId=22222222-2222-4222-8222-222222222222'
```

Stream zuerst in einem zweiten Terminal öffnen, dann senden.
Zum Wiederaufnehmen zusätzlich `-H 'Last-Event-ID: <zuletzt empfangene UUID>'`
an curl übergeben. Bei mehreren Instanzen spricht eine Prüfung jede Instanz
gezielt über ihre aktuelle Container-Adresse an.

## Aufbau

Im `chat-service/src/main/java/ch/benedict/m321/chatservice/`:

- `controller`: POST- und SSE-Vertrag, Parameterprüfung.
- `messaging/LiveMessageConsumer`: eine Live-Kopie aus RabbitMQ entgegennehmen.
- `service/MessageService`, `MessageTimestamp`, `MessagePublisher`: Identität,
  monotone Mikrosekunden-Zeit je Instanz und geordnete Veröffentlichung.
- `service/StreamService`, `StreamSession`: Räume, Verbindungen, begrenzte Ausgabe.
- `service/ReplayService`, `RecentMessageBuffer`: Historie und Live-Übergabe.
- `repository/MessageHistoryRepository`: ausschliesslich parametrisierte SQL-Leseabfragen.
- `config`: Queue-, Listener-, Publisher- und Pufferkonfiguration.

Der Writer-Weg ist `MessageConsumer` → `MessageReader` → `BatchWriteService` →
`MessageRepository` → PostgreSQL, danach ACK. Controller und Dienste reichen hier
aus; zusätzliche Router oder leere Interfaces würden keine weitere Aufgabe erfüllen.

## Betrieb und Dokumente

`docker compose down` erhält die Datenvolumes; `docker compose up -d` startet erneut.
Kein `down -v` für normale Neustarts oder Abnahmeszenarien verwenden.
Das Writer-Schema wird beim ersten PostgreSQL-Start mit leerem Datenvolume
initialisiert. Eine geänderte `.env` initialisiert vorhandene Daten nicht neu.
Der Chat-Service führt keine Schema-Initialisierung oder SQL-Schreibzugriffe aus.

- [Spezifikation Bewertung 2](docs/spec-live-zustellung.md): eigene Darstellung des Lernenden und S1–S8.
- [Umsetzungsplan Bewertung 2](docs/plan-live-zustellung.md): vor Code gepushter Plan, KI-Aufträge und Prüfnotizen.
- [Spezifikation Bewertung 1](docs/spec-batch-writer.md), [Plan](docs/plan-batch-writer.md), [Abnahme](docs/abnahme-batch-writer.md): abgeschlossene Persistenzaufgabe.
- [CLAUDE.md](CLAUDE.md): Regeln für lesbaren Code, deutsche Kommentare und Dokumentation.
- [PLANUNG.md](PLANUNG.md), [grafischer Entwurf](docs/design/2026-08-28-chat-app-planung.html),
  [Vorlagenplan](docs/plan-chat-service.md), [Flipchart](docs/flipchart-chat-app.png): historische Gesamtplanung;
  die Bewertungsspezifikationen bestimmen den tatsächlichen Umfang.

## Abgabe Bewertung 2

Gleicher Fork wie bei Bewertung 1, fertiger Stand auf `main`, Tag `bewertung-2`
auf dem geprüften Stand erstellen und pushen. Anschliessend Fork-Link im
Abgabeportal einreichen. Termin laut Unterrichtsmitteilung: **13.11.2026 gegen
Tagesende**. `bewertung-1` bleibt auf seinem ursprünglichen Stand.
Eigene Tests belegen eigene Prüfungen; Lehrerskript und späteres Code-Review-Gespräch
sind eigenständige Bewertungsteile und keine vorab garantierten Punkte.
