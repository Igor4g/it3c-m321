# Eigene Abnahme — Bewertung 2: Live-Zustellung

**Eigene Schlussprüfung vom 13.11.2026, Europe/Zurich: S1–S8 im frischen Klon bestanden.**

## Geprüfter Stand und Umgebung

Code und Prüfwerkzeuge stammen aus Commit
`2a7cff5498dbb148d58056833c44cc779a339418`. Ein neuer lokaler Git-Klon dieses
Commits enthält ausschliesslich versionierte Dateien. `.env` wurde unverändert
aus `.env.example` kopiert; die Übereinstimmung wurde vor dem Start geprüft.
Es wurde kein Inhalt der persönlichen `.env` übernommen.

Umgebung: Windows, JDK 21.0.9, Maven 3.9.9, Windows PowerShell 5.1,
Docker Engine 27.5.1 und Docker Compose 2.32.4. RabbitMQ, PostgreSQL und die
Java-Anwendungen laufen tatsächlich in Containern; es gibt keine gemockte
Verteilung. Der Node-22-Prüfclient verwendet nur eingebaute Bibliotheken.

Der persönliche Compose-Stack wurde vorher mit `docker compose down` ohne `-v`
beendet. Seine benannten Datenvolumes blieben erhalten. Der Prüfklon verwendet
`COMPOSE_PROJECT_NAME=m321-b2-abnahme`, eigene neue Datenvolumes und allein das
Netz `chat-net`. Zwischen S2 und S8 werden weder Stackdaten noch Queues zurückgesetzt.
Jedes Szenario verwendet neue Raum-UUIDs.

## Befehle und Ergebnisse

Im neuen Klon:

```powershell
Copy-Item .env.example .env
mvn clean test
$env:COMPOSE_PROJECT_NAME = 'm321-b2-abnahme'
powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario All
```

`All` startet mit `docker compose up -d --build` und prüft S2–S8 in der
vorgeschriebenen Reihenfolge auf demselben Stack.

| Szenario | Soll | Ist im frischen Klon |
|---|---|---|
| S1 | Ein Maven-Gesamtlauf grün | 137 Tests, 0 Fehler, 0 übersprungen; BUILD SUCCESS um 16:02:35, Dauer 7:42 min |
| S2 | Alle Dienste laufen, keine Host-Ports | 4 Dienste, 0 veröffentlichte Ports; frischer Build/Start in 102.101 s |
| S3 | Beide A-Clients je 400, B leer, einmalig und geordnet | 400/400/0; alle IDs und sechs JSON-Felder gleich, 0 Duplikate, Absenderfolge korrekt |
| S4 | Zwei Live-Queues mit je einem Consumer auf eine in höchstens 30 s | Entfernung in 8.772 s; danach eine Queue; chat.persist und chat.dlq erhalten |
| S5 | 100 live in höchstens 5 s ohne DB/Writer; danach dieselben 100 gespeichert | 922 ms ab erstem POST, 12 ms ab letztem POST; exakt dieselben 100 IDs in PostgreSQL |
| S6 | Aktiver Client alle 5000; pausierte Queue höchstens 1000; danach neue Zustellung | 5000 vollständig und einmalig; Maximum ready + unacked = 1000; neue ID nach Fortsetzen empfangen |
| S7 | Nach 10 s genau 50 fehlende Nachrichten, danach Live | 50 geordnet und einmalig, Cursor ausgeschlossen; danach 1 neue Live-Nachricht |
| S8 | Kommentare, keine Java-Streams, keine versionierte .env | CodeStyleTest grün, Sprache/Lesbarkeit geprüft, .env und tempContext nicht versioniert |

Die JUnit-XML-Berichte bestätigen 36 Chat-Service- und 101 Writer-Tests ohne
Fehler oder Auslassungen. Die Compose-Ergebnisdateien entstehen unter
`chat-service/target/acceptance/`; sie sind ignorierte lokale Prüfartefakte.

Alle sieben Compose-Ergebnisse stehen im selben `All`-Lauf auf `Passed: true`,
Exit-Code 0. Die Gesamtdauer der Szenarien S2–S8 beträgt 263.612 s einschliesslich
Build, Neustarts und Wartezeiten; sie ist nicht mit der Live-Frist von S5 zu verwechseln.

Messräume: S3 `a511bde5-4916-4e27-af1c-a648e47d1eb8`,
S5 `37b8f899-75d8-4f70-bca8-e59fada799d8`,
S6 `2d27d74a-53c5-47b8-b398-cda63cd28a05`,
S7 `388650b9-8794-484a-9ab9-00c86eade199`.
Neue S6-Nachricht nach Fortsetzen: `952b2af2-aeb9-465d-816c-a7a30279c61a`.
S7-Cursor: `c2112db7-5503-43d6-a0a5-219806980e2b`.
Die vollständigen Soll-/Ist-Mengen werden im Client verglichen; fehlende oder
zusätzliche IDs und Duplikate führen zu einem Fehler statt einem grünen Zähler.

Sieben Queue-Stichproben je Live-Queue: `spring.gen-qOiPQZOPTnWf3geaJeZguQ`
erreichte maximal 1000, `spring.gen-KM0bch54T-aaZsPA6IOFJQ` maximal 0.
Die Stichproben und AMQP-Argumente sind in `queue-samples.json` festgehalten.

## Was tatsächlich verglichen wird

Der HTTP-Client liest vollständige SSE-Ereignisse, verarbeitet UTF-8 auch über
Paketgrenzen und zählt keine Kommentare/Heartbeats. Er verlangt SSE-`id` gleich
JSON-`id`, exakte ID-Mengen ohne Duplikate, alle sechs unveränderten JSON-Felder
und die Reihenfolge jedes Absenders. Raum B bleibt während S3 verbunden.
S7 schliesst den Cursor selbst aus und prüft nach der Nachlieferung neue Live-Daten.

Bei S4 bleiben SSE-Verbindungen während Scale-down offen. Die gemessene Zeit
beginnt vor dem Scale-down-Befehl; sie umfasst auch den anschliessenden
Broker-Nachweis. Die ursprünglichen zwei Live-Queues haben je einen Consumer.
`chat.persist` und `chat.dlq` müssen erhalten bleiben.

Bei S5 beginnt die konservative Zeitmessung vor dem ersten POST und endet beim
Empfang der hundertsten Nachricht. Zusätzlich wird die Zeit nach dem letzten
erfolgreichen POST gemessen. Nach dem Neustart müssen genau die 100 zuvor über
HTTP vergebenen IDs im selben Raum in PostgreSQL stehen. Gezählt wird nicht nur
eine beliebige Menge von 100 Zeilen.

Bei S6 wird während der Pause `messages_ready + messages_unacknowledged` gelesen.
Zusätzlich werden die echten Broker-Argumente `x-max-length=999`, `drop-head` und
ein Consumer geprüft. `prefetch=1` lässt höchstens eine unbestätigte Lieferung zu.
Die Stichproben dokumentieren Messpunkte; die Konfiguration begrenzt die Summe
auch dazwischen. Nach dem Fortsetzen muss eine eindeutig neue Nachrichten-ID an
der zuvor pausierten Instanz eintreffen. Kein manueller Dienstneustart ersetzt das.

## Weitere automatisierte Prüfungen

`LiveDistributionIntegrationTest` prüft zwei echte HTTP-Anwendungen mit RabbitMQ,
je 200 POSTs und drei SSE-Clients. `StreamReconnectIntegrationTest` enthält neun
Fälle mit echtem RabbitMQ und PostgreSQL: S7 mit Instanzwechsel, noch nicht
gespeicherter Cursor, während gesperrtem SQL eintreffende Ereignisse, alter
Zeitstempel eines neuen Ereignisses, späte Broker-Kopie einer gelesenen SQL-Zeile,
UUID-Gleichstände, Fehlerstatus und Puffergrenzen. Reine Live-Verbindungen
funktionieren im DB-Ausfalltest weiter.

Zeitstempeltests prüfen gleiche und rückläufige Uhr. SQL-Tests prüfen auch UUIDs
oberhalb des Vorzeichenbits; RAM- und Sitzungstests prüfen Begrenzung, FIFO und
die Isolation eines blockierten Clients. Kleine konfigurierte Grenzwerte testen
den Überlauf ohne unnötige zehntausendfache Testvorbereitung.

`CodeStyleTest` prüft Klassen-/Methodenkommentare und echte Stream-Verwendungen
am Java-Syntaxbaum. Deutsche Sprache und verständliche Zweckbegründungen wurden
zusätzlich gelesen. Die versionierten Texte wurden auf kyrillische Zeichen
kontrolliert. Private Lernunterlagen bleiben in der ignorierten `tempContext/`.

## Prüffunde und Abgrenzungen

Die Prüfnotizen im [vorab veröffentlichten Plan](plan-live-zustellung.md) halten
die Korrekturen je Schritt fest. Im Gesamtlauf wurde ein veralteter Überlauftest
gefunden: eine wiederholte ID muss ignoriert werden und darf keinen weiteren
Pufferplatz belegen. Jetzt werden Duplikat und echte Überfüllung getrennt geprüft.
Ein RabbitMQ-Start überschritt während parallelem Docker-Build den Testcontainer-
Timeout; der anschliessende vollständige sequenzielle Lauf war grün.

Im Compose-Prüfwerkzeug mussten PowerShell-5.1-JSON-Arrays, AMQP-Argumentlisten
und die BOM des ersten Steuersignals berücksichtigt werden. Ein bereits laufendes
`--rm` verdeckt jetzt keinen Originalfehler mehr. Der frische Schlusslauf prüft
auch die anschliessend ergänzte PostgreSQL-Bereitschaft und Artefaktbereinigung.

Der Plan wurde als Commit `27f50377136778096ec592ffe2b750697440c956` vor dem
ersten Code veröffentlicht und mit Remote-main verglichen. Sein ursprünglicher
Text bleibt eingefroren; aktuelle Ergebnisse stehen ausschliesslich in den
vorgesehenen Prüfnotizen. Die Spezifikation des Lernenden wurde nicht nachträglich
an Testergebnisse umgeschrieben. Historische Gesamtpläne sind als solche markiert.

Die Nachweise betreffen die eigenen Prüfungen und die vier geforderten Abgaben.
Das unbekannte Lehrerskript und die 16 Punkte im späteren Code-Review-Gespräch
sind damit nicht vorab vergeben. Die Vorbereitung des Lernenden folgt separat.
Eine globale Exactly-once-Zustellung oder Reihenfolge konkurrierender Instanzen
wird nicht behauptet; die vereinbarten Grenzen stehen in Spezifikation und Plan.

Nach dieser Abnahme folgt ausschliesslich ein Dokumentationscommit mit Bericht,
README, Einordnung historischer Dokumente und Prüfnotizen. Anwendungscode,
Tests und Betriebskonfiguration bleiben gegenüber dem genannten Prüfcommit
unverändert. `bewertung-2` bezeichnet den vollständigen Abgabestand einschliesslich
dieser Dokumentation; `bewertung-1` bleibt auf seinem ursprünglichen Commit.
