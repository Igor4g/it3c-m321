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
mvn test                         # alle Tests, RabbitMQ kommt per Testcontainers
docker compose up --build        # RabbitMQ und chat-service im Netz chat-net
```

Der `chat-service` veröffentlicht bewusst **keinen Port** auf den Host. Der einzige offene Port
des Gesamtsystems gehört später dem Gateway.

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank | Grundgerüst mit Starttest; Queue und DB folgen |
| postgres | PostgreSQL | Speichert den Chat-Verlauf | folgt |
| keycloak | Keycloak | Login (OIDC) | folgt |
| web-gateway | nginx | Einziger nach aussen offener Port | folgt |
| Web-UI | React | Browser-Client | folgt |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — Umsetzung in kleinen Schritten mit Tests und eigenständigen Commits.

- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation für Bewertung 1; Abnahme noch ausstehend.

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
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
