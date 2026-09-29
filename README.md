# M321 — Chat-App (Klasse IT3c)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3c-m321.git
   cd it3c-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Maven**, **Docker Desktop**, **Git**.
4. Lokale Umgebungsdatei anlegen und die Werte anpassen:
   ```bash
   cp .env.example .env
   ```
5. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

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

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank | implementiert; in Compose eingebunden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf | Schema, Datenvolume und Integrationstests vorhanden |
| keycloak | Keycloak | Login (OIDC) | folgt |
| web-gateway | nginx | Einziger nach aussen offener Port | folgt |
| Web-UI | React | Browser-Client | folgt |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`docs/abnahme-batch-writer.md`](docs/abnahme-batch-writer.md) — gemessene Ergebnisse für Last, Duplikate, Skalierung und DB-Ausfall; vollständige Schlussabnahme noch offen.

- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Umsetzung in kleinen Schritten mit Tests und eigenständigen Commits.

- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation für Bewertung 1; Abnahme noch ausstehend.

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge des Gesamtprojekts. Für Bewertung 1 gelten der Lehrerauftrag
  und die daraus abgeleitete Batch-Writer-Spezifikation; spätere Ausbaustufen sind nicht Teil der Abgabe.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
