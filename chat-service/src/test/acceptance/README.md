# Compose-Abnahme der Live-Zustellung

Diese Werkzeuge prüfen S2–S8 zusätzlich zu den JUnit-Tests. Sie verändern keine
Produktkonfiguration, veröffentlichen keine Ports und löschen weder Queues noch
Datenvolumes. Neue UUID-Räume trennen die Messreihen.

## Vollständiger Lauf

Voraussetzungen: Windows PowerShell 5.1 oder PowerShell 7, Docker Compose v2,
Git, Java 21 und Maven 3.9. Docker muss laufen; `.env` wird im frischen Klon aus
`.env.example` kopiert. Aus dem Projektstamm:

```powershell
mvn clean test
powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario All
```

Der erste Befehl ist S1. `All` führt S2, S3, S4, S5, S6, S7 und S8 auf demselben
Stack aus, ohne dessen Daten zwischen den Szenarien zurückzusetzen. Für S2 wird
ein neuer Stack mit anfangs einer Chat-Instanz erwartet. Ein neuer Klon darf nicht
parallel zum persönlichen Stack laufen, weil beide das Netz `chat-net` verwenden.
Den persönlichen Stack vorher mit `docker compose down` **ohne `-v`** beenden.
Ein anderer Compose-Projektname gibt dem Prüfklon eigene Datenvolumes.

Ergebnisse liegen unter `chat-service/target/acceptance/results.json`;
`queue-samples.json` enthält die Stichproben während der Pause in S6.
Ein Fehler ergibt Exit-Code 1 und `failure.txt`. Nur tatsächlich bestandene
Szenarien erhalten `Passed: true`. Ein fehlgeschlagener Lauf zählt nicht als
Gesamtabnahme. Alte Artefakte bei der Auswertung nicht mit einem neuen Lauf mischen.

## Gezielt nachprüfen

`-Scenario S3` bis `-Scenario S8` führen einzelne Prüfungen aus; sie ersetzen den
Gesamtlauf nicht. S4 erwartet die beiden Instanzen aus S3. S6 stellt die zweite
Instanz erneut bereit. S8 benötigt einen erfolgreichen `CodeStyleTest`-XML-Bericht
aus dem vorherigen Maven-Lauf. Kommentare werden zusätzlich auf deutsche Sprache
und verständliche Begründungen gelesen, weil der Java-Parser nur deren Existenz prüft.

## Aufgaben der beiden Skripte

`verify-live.ps1` steuert Docker auf dem Host und liest Queue-/SQL-Zustände.
Ein vorübergehender `node:22-alpine`-Container führt `live-client.mjs` innerhalb
von `chat-net` aus. Er besitzt weder Docker-Socket noch Zugangsdaten. Er verwendet
nur Node-Bordmittel, HTTP und vollständige SSE-Ereignisse; kein npm-Install nötig.
Die Instanz-Adressen werden nach Skalierung neu ermittelt.

Der Client meldet explizite Bereitschaft an PowerShell. Erst dann stoppt oder
pausiert der Host Dienste. S4 hält SSE-Verbindungen während Scale-down offen.
S5 misst konservativ vom ersten POST bis zum letzten Empfang und vergleicht
anschliessend exakt dieselben 100 IDs mit PostgreSQL. S6 misst `ready + unacked`
während der Pause und kontrolliert zusätzlich `x-max-length=999`, `drop-head` und
einen Consumer. Die Stichproben sind kein lückenloses Sampling; die feste Queue-
Grenze und `prefetch=1` begrenzen die Summe auch zwischen den Messungen auf 1000.
S7 trennt nach dem Cursor, sendet 50, wartet 10 Sekunden und nimmt an der anderen
Instanz wieder auf; anschliessend folgt eine neue Live-Nachricht.

Verglichen werden IDs, Duplikate, alle sechs JSON-Felder und die Absenderfolge,
nicht nur Log-Zeilen. Raum B bleibt während S3 offen. Bei Fehlern werden eigene
Clients beendet, pausierte Container fortgesetzt und angehaltene Persistenzdienste
wieder gestartet. Die Aufräumphase macht ein fehlgeschlagenes Szenario nicht grün.
