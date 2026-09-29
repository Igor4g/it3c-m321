# Batch-Writer: gemessene Abnahmeergebnisse

Stand: 29.09.2026, Schritt 7. Geprüfter Anwendungscode und Compose-Konfiguration:
Commit `36eb6aebad688be193aa0a51ee3209cfc7c49929`.
Die anschliessenden Änderungen betreffen nur Prüfbefehle und Dokumentation.

Dies ist die eigene Prüfung anhand des Lehrerauftrags. Das unbekannte Prüfskript
des Lehrers wurde nicht ausgeführt. Eine vollständige Schlussabnahme S1–S8 auf
einem frischen Klon und das Code-Review-Gespräch sind noch offen.

## Umgebung und Vorgehen

- Windows, PowerShell 7.5.1, Docker Compose 2.32.4, Java 21.
- PostgreSQL 16-alpine und RabbitMQ 3.13-management im Compose-Netz chat-net.
- BATCH_SIZE=100, BATCH_TIMEOUT_MS=200, RETRY_DELAY_MS=1000.
- HTTP-Aufrufe über einen temporären curl-Container innerhalb des Netzes.
- SQL über docker compose exec; keine veröffentlichten Host-Ports.
- S3–S7 auf demselben Stack, ohne Löschen von Nachrichten, Queues oder Volumes.
  Wiederholte Prüfungen nutzten jeweils neue Raum-IDs.
- Für jede Sendeserie wurden alle zurückgegebenen IDs aufgezeichnet und mit den
  gespeicherten IDs verglichen. Zusätzlich wurde die Gesamtzahl im jeweiligen Raum geprüft.
- Queue-Prüfungen erfassen ready **und** unacknowledged. Ein HTTP 202 allein
  zählt nicht als Nachweis einer Speicherung.

Die ausführbaren Befehle und Hilfsfunktionen stehen in
[Spezifikation, Abschnitt 6.1](spec-batch-writer.md#61-gemeinsame-regeln).
Lokale Rohprotokolle und ID-Listen liegen in der ignorierten tempContext;
sie sind für den Start und die Wiederholung der dokumentierten Befehle nicht erforderlich.

## Ergebnisse

| Szenario und Befehl | Soll | Gemessen | Ergebnis |
|---|---|---|---|
| S3: Send-Messages mit 1000, danach Wait-ForMessages | 1000 IDs genau einmal, Queue leer innerhalb 60 s | Senden 7,70 s; Nachweis danach 1,40 s; insgesamt 9,10 s | bestanden |
| S4: Writer stoppen, 1000 senden, Writer starten; Get-TransactionCount davor/danach | Rückstau nachgewiesen; höchstens 100 DB-Transaktionen | Vorher 1000 ready, 0 unacknowledged, 0 Consumer; danach alle IDs und leere Queue; 6,22 s ab Writer-Start; 20 Transaktionen | bestanden |
| S5: zweimal dasselbe JSON über RabbitMQ-API, nur content_type gesetzt | Eine unveränderte Zeile, keine neue DLQ-Nachricht | Eine Zeile; alle sechs Felder unverändert; DLQ leer; Prüfung 5,21 s | bestanden |
| S6: docker compose up -d --scale batch-writer=2; 1000 senden | Zwei Consumer an derselben Queue; 1000 IDs ohne Duplikate | Zwei Consumer; jeder Writer laut Logs 500 Nachrichten; Senden 6,35 s, Nachweis danach 1,34 s; Queue leer | bestanden |
| S7: PostgreSQL stoppen, 300 senden, nach 15 s starten | Alle 300 IDs innerhalb 90 s; keine Writer-Neustarts oder neue DLQ-Nachrichten | Senden während Ausfall 2,92 s; im Broker 100 ready + 200 unacknowledged; vollständige Erholung in 21,12 s | bestanden |

Für S3/S6 beginnt die Wartezeit nach Abschluss des Sendens. S4 misst ab dem
Startbefehl des Writers. S7 misst ab dem abgeschlossenen DB-Stop; die 15 Sekunden
Ausfall sind in den 21,12 Sekunden enthalten. Alle Zeitwerte enthalten die
jeweiligen Prüfaufrufe und sind keine isolierten SQL-Laufzeiten.

Bei S7 wurden Container-ID, StartedAt und RestartCount beider Writer vor und
nach dem Ausfall verglichen: unverändert, RestartCount jeweils 0.
Beide Writer meldeten DB-Fehler und später erfolgreiche Speicherung.
Am Ende: chat.persist 0 ready/0 unacknowledged bei 2 Consumern;
chat.dlq 0 ready/0 unacknowledged.

## S4: vollständiger Transaktionsnachweis

Gemessen wurde xact_commit + xact_rollback für die Anwendungsdatenbank,
abgefragt aus der Wartungsdatenbank postgres:

- Vorher: 300; nachher: 320; Differenz: **20**, Grenzwert: **100**.
- Die Writer-Logs zeigen zehn gespeicherte Batches mit jeweils 100 Nachrichten.
- Die Differenz enthält auch Prüf-SELECTs und weitere Transaktionen in der
  Anwendungsdatenbank. Es wurden keine vermeintlichen Messkosten abgezogen.
- Nach erfolgreicher Speicherung und leerer Queue wurde der Writer kurz sauber
  gestoppt, um die DB-Verbindung zu schliessen und deren Statistik vollständig
  zu veröffentlichen. Danach wurde er wieder gestartet. Daten und Statistikzähler
  wurden nicht gelöscht oder zurückgesetzt.

Der erste Messversuch ergab vorzeitig nur 6 zusätzliche Transaktionen. Dieser
Wert wurde wegen der zehn geloggten Schreibbatches als unvollständig verworfen.
PostgreSQL veröffentlicht kumulative Statistiken zeitversetzt; nach der Anpassung
wurde S4 mit neuen 1000 Nachrichten vollständig wiederholt.
[PostgreSQL 16: Statistikaktualisierung](https://www.postgresql.org/docs/16/monitoring-stats.html#MONITORING-STATS-VIEWS).

## Korrigierte Prüfprobleme

1. Der erste Sendeversuch brach wegen Windows-CRLF im Linux-Shellskript ab.
   Die Übertragung entfernt jetzt CR-Zeichen vor der Shell-Ausführung.
2. Der erste S4-Statistikwert war unvollständig; siehe Messverfahren oben.
3. Beim ersten S7-Versuch interpretierte Receive-Job die Fortschrittsausgabe von
   Docker auf stderr als PowerShell-Fehler. PostgreSQL war bereits wieder gestartet,
   die Writer verarbeiteten weiter. Dieser abgebrochene Messlauf wurde nicht als
   bestandener Test gewertet. Nach Umwandlung des Job-Fortschritts in Text wurde
   S7 mit weiteren 300 Nachrichten wiederholt und vollständig geprüft.

Keine dieser Korrekturen erforderte eine Änderung des Anwendungscodes.

## Bereits geprüft und noch offen

- Schritt 6: mvn clean test vom Projektstamm, 99 Tests ohne Fehler/Überspringen;
  Docker-Build, Schema/Index, keine Host-Portbindungen und Datenbestand nach
  Containerneuerstellung erfolgreich geprüft.
- Schritt 7: S3–S7 wie oben gemessen. Da nur Dokumentation und Prüfbefehle geändert
  wurden, wurden die unveränderten Java-Tests nicht erneut gestartet.
- Offen: frischer Klon, vollständige Reihenfolge S1–S8 ohne Zwischenbereinigung,
  abschliessender Abgleich mit dem Lehrerauftrag und Code-Stil, Veröffentlichung
  des geprüften main-Stands und Tag bewertung-1.
- Die Vorbereitung auf das spätere Code-Review-Gespräch folgt nach Fertigstellung
  des Abgabeprojekts. Die gemessenen Ergebnisse ersetzen dieses Gespräch nicht.
