# M321 — Bewertung 1: Batch-Writer

Lernprojekt zum Modul **M321 Verteilte Systeme programmieren**, Klasse IT3c.
Der Batch-Writer holt Chat-Nachrichten aus RabbitMQ und speichert sie stapelweise
in PostgreSQL. Er bestätigt sie erst nach COMMIT, verhindert doppelte Zeilen
und arbeitet nach einem vorübergehenden Datenbankausfall selbstständig weiter.
Der vorhandene `chat-service` stammt aus der Unterrichtsvorlage.

## Projekt starten

1. Voraussetzungen: **Java 21**, **Maven 3.9**, **Docker mit Compose v2**, **Git**.
   Docker muss auch für die Integrationstests laufen.
2. Diesen Fork klonen:
   ```bash
   git clone https://github.com/Igor4g/it3c-m321.git
   cd it3c-m321
   ```
3. Lokale Umgebungsdatei anlegen:
   ```bash
   cp .env.example .env
   ```
   Die Beispielwerte reichen für den lokalen Bewertungsstack. Die echte `.env`
   bleibt lokal und wird nicht eingecheckt.
4. Die folgenden Prüf- und Startbefehle im Projektstamm ausführen.

Die aktuelle Spezifikation, der Plan und die gemessenen Ergebnisse sind unten
verlinkt. Die Nachprüfung vom 30.09. ergänzt den Schutz vor nicht speicherbaren
Zeitpunkten und bereinigt die Lesbarkeit. Das spätere Code-Review-Gespräch ist
ein eigener Bewertungsteil; eigene Tests sind keine bereits vergebenen Lehrerpunkte.
Die erneute eigene Abnahme S1–S8 vom **30.09.2026** ist im
[Abnahmebericht](docs/abnahme-batch-writer.md) dokumentiert.

## Bauen, testen, starten

```bash
mvn clean test                   # alle Tests, RabbitMQ und PostgreSQL per Testcontainers
docker compose config --quiet    # Konfiguration und benötigte .env-Werte prüfen
docker compose up -d --build     # alle vier Dienste im Netz chat-net starten
docker compose ps               # Status anzeigen
docker compose logs batch-writer # Verarbeitung und mögliche DB-Fehler anzeigen
```

Keiner der vier Dienste veröffentlicht einen Port auf dem Host. HTTP-Aufrufe an
`chat-service:8080` erfolgen aus dem Netz `chat-net`. Das spätere Gateway gehört
nicht zu Bewertung 1.

Die Integrationstests benötigen einen laufenden Docker-Daemon. Für den Ausfalltest
wird ein Testcontainer für 15 Sekunden gestoppt; der Compose-Stack bleibt unberührt.
Die Suite umfasst **115 Tests**, einschliesslich Duplikaten, DB-Ausfall,
ungültigen Nachrichten neben gültigen sowie den Zeitgrenzen von JDBC/PostgreSQL.
Zusätzliche Fehlertests prüfen Rollback nach dem SQL-Batch und Wiederzustellung
bei Verbindungsabbruch zwischen COMMIT und ACK.

Zwei Writer lassen sich ohne Änderungen am Code starten:

```bash
docker compose up -d --scale batch-writer=2
```

## Eine Nachricht durch den Stack senden (PowerShell)

Nach dem Start aller Dienste:

```powershell
$body = '{"roomId":"22222222-2222-4222-8222-222222222222","senderId":"anna","senderName":"Anna Muster","content":"Hallo aus Compose"}'
$body | docker run --rm -i --network chat-net curlimages/curl:8.10.1 --silent --show-error --fail-with-body -H 'Content-Type: application/json' --data-binary '@-' http://chat-service:8080/messages
```

Die Antwort enthält die erzeugte Nachrichten-ID. Der curl-Container ist ein
vorübergehendes Prüfwerkzeug innerhalb des Netzes und wird anschliessend entfernt.
HTTP 202 bestätigt die Annahme; die Speicherung erfolgt kurz danach durch den Writer.

```powershell
# Bereits gespeicherte Nachrichten anzeigen; Zugangsdaten kommen aus dem Container.
docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "SELECT id, content, sent_at FROM message ORDER BY sent_at DESC LIMIT 5;"'
```

## Daten und Betrieb

`docker compose down` beendet die Dienste und erhält die benannten Datenvolumes.
`docker compose up -d` startet sie wieder. `down -v` löscht dagegen die gespeicherten
Nachrichten und Queues und ist kein normaler Neustart.

PostgreSQL führt `batch-writer/src/main/resources/schema.sql` beim ersten Start mit
leerem Datenvolume aus. Ein normales Neustarten überschreibt weder Schema noch Daten.
Änderungen an Schema oder initialen Zugangsdaten eines bestehenden Volumes brauchen
eine bewusste Anpassung; das Ändern von `.env` initialisiert keine bestehende DB neu.

Die Beispiele in `.env.example` dienen dem lokalen Unterricht. Die eigene `.env`
und `tempContext/` bleiben aus Git und aus dem Docker-Build-Kontext ausgeschlossen.
Der Writer nutzt standardmässig 100 Nachrichten pro Batch, 200 ms Sammelzeit und
1000 ms Pause nach einem DB-Fehler. Die Einstellungen stehen in `.env.example`.

## Umfang dieser Abgabe

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank | implementiert; in Compose eingebunden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf | Schema, Datenvolume und Integrationstests vorhanden |
Login/Keycloak, Gateway, Oberflächen, Chat-Historie, Raumverwaltung und
Lastgenerator gehören zum späteren Gesamtprojekt und nicht zu Bewertung 1.
Das allgemeine Lastziel von 100'000 Nachrichten/Minute ist hier nicht als erreicht behauptet.

## Dokumente

- [`docs/abnahme-batch-writer.md`](docs/abnahme-batch-writer.md) — eigene Abnahme S1–S8, Messwerte und dokumentierte Prüfkorrekturen.

- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Umsetzung in kleinen Schritten mit Tests und eigenständigen Commits.

- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Vertrag, Fehlerverhalten und ausführbare Abnahmekommandos für Bewertung 1.

- [`PLANUNG.md`](PLANUNG.md) — ursprünglicher Gesamtentwurf und Referenz für das
  Datenmodell; der Hinweis am Dokumentanfang grenzt ihn vom tatsächlichen Abgabestand ab.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — historische grafische Fassung des Gesamtentwurfs, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der bereitgestellte `chat-service` gebaut wurde; als historische Vorlage gekennzeichnet.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht; die HEIC-Datei daneben ist das Originalbild.

## Abgabe

Abzugeben ist der Link zu diesem Fork bis **02.10.2026, 23:59 Uhr**.
Die Lehrperson prüft den veröffentlichten Tag `bewertung-1`; er muss auf den
gewünschten geprüften Commit auf `main` zeigen. Ein späterer Commit auf `main`
ändert einen bereits gesetzten Tag nicht automatisch. Der Abnahmebericht nennt
den geprüften Code-Stand. Die Einreichung des Links erfolgt separat im Abgabeportal.

## Aufbau des Writers

Der Ablauf ist: Queue → MessageConsumer → MessageReader → BatchWriteService →
MessageRepository → PostgreSQL. Erst nach erfolgreichem COMMIT bestätigt der
Consumer die betreffenden Nachrichten an RabbitMQ.

| Datei unter batch-writer/src/main | Verantwortung |
|---|---|
| java/.../messaging/MessageConsumer.java | Lieferungen prüfen lassen, speichern, ACK/NACK und Wiederzustellung steuern |
| java/.../messaging/MessageReader.java | JSON und die sechs Vertragsfelder prüfen |
| java/.../messaging/InvalidMessageException.java | Dauerhafte Datenfehler von technischen DB-Fehlern unterscheiden |
| java/.../dto/ChatMessage.java | Die geprüften Nachrichtenwerte als record transportieren |
| java/.../service/BatchWriteService.java | Eine Transaktion je nicht leerem Batch abgrenzen |
| java/.../repository/MessageRepository.java | Parametrisiertes SQL mit Schutz vor doppelten IDs ausführen |
| java/.../config/RabbitConfig.java und BatchProperties.java | Queues, Batch-Grenzen und Retry-Pause konfigurieren |
| resources/schema.sql und application.yml | Datenmodell und Verbindungseinstellungen festlegen |

Der Writer hat keine HTTP-Schnittstelle und keine View. Deshalb reichen diese
Zuständigkeiten ohne zusätzliche Router, Interfaces oder ein eigenes Batch-Framework.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
