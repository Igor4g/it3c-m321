# Batch-Writer: gemessene Abnahmeergebnisse

Stand: **30.09.2026**, erneute eigene Abnahme nach den Auditkorrekturen abgeschlossen.
Geprüfter Code-Stand: **`bf36be7`**, einschliesslich Zeitbereichsprüfung aus `75ef9d9`.
Die abschliessende Gegenprüfung ergänzt Tests und Nachweise;
Anwendungscode und Compose-Konfiguration bleiben unverändert.

Dies ist die eigene Prüfung anhand des Lehrerauftrags. Das unbekannte Prüfskript
des Lehrers wurde nicht ausgeführt. Das Code-Review-Gespräch und die Einreichung
des Fork-Links durch den Lernenden sind separate Schritte.

## Aktuelle Schlussabnahme vom 30.09.2026

S1 lief vom Arbeitsprojektstamm mit dem korrigierten Code. Für S2–S7 wurde davon
ein frischer lokaler Klon mit `git clone --no-local` erstellt, .env ausschliesslich
aus .env.example kopiert und mit neuen leeren Volumes gestartet. Die Szenarien
liefen in Reihenfolge auf demselben Stack, ohne Bereinigung dazwischen.

| Szenario | Ergebnis nach der Korrektur |
|---|---|
| S1 | Abschliessendes `mvn clean test`: **115 Tests**, 0 Fehler, 0 übersprungen; 14 im chat-service und 101 im Writer |
| S2 | Frischer Klon, `docker compose up -d --build`: vier laufende Dienste, keine Host-Portbindungen; Tabelle, Primärschlüssel und Raum/Zeit-Index automatisch vorhanden |
| S3 | 1000 IDs genau einmal, Queue leer; Senden samt Prüfung **10,25 s**, Grenze 60 s |
| S4 | Rückstau von 1000 Nachrichten vollständig gespeichert; **20 zusätzliche DB-Transaktionen**, Grenze 100 |
| S5 | Identischer JSON-Body zweimal, nur application/json: eine unveränderte Zeile, DLQ leer |
| S6 | Zwei Consumer, 1000 IDs genau einmal; laut Logs **je 500 Nachrichten** |
| S7 | PostgreSQL nach 15 s wieder gestartet; alle 300 IDs samt Schlussprüfung nach **24,58 s**, Grenze 90 s; beide Writer-Prozesse unverändert |
| S8 | Deutsche Klassen-/Methodenkommentare aktualisiert, mehrstufige Berechnungen aufgeteilt; keine Streams, keine getrackte .env oder tempContext; Themencommits und Plan stimmen überein |

Endzustand: **3301 Zeilen**, chat.persist und chat.dlq jeweils 0 ready und
0 unacknowledged. S7 prüfte Container-ID, StartedAt und RestartCount beider Writer:
unverändert. Die 15 Sekunden DB-Ausfall sind in der gemessenen Erholungszeit enthalten.

Zusätzliche Regression: Java-lesbare, aber nicht speicherbare Zeitpunkte werden
vor JDBC abgelehnt. Drei ungültige Zeitwerte wurden mit echtem Broker und echter
DB neben gültigen Nachrichten getestet: nur der fehlerhafte Body landete in der DLQ.
Beide erlaubten Zeitgrenzen wurden über denselben Weg unverändert gespeichert.
Der erste Grenztest deckte pgJDBCs Umwandlung früher Daten in -infinity auf;
die endgültige untere Grenze berücksichtigt deshalb ausdrücklich den Treiber.

Die Prüfung der Abgabeunterlagen umfasst alle 55 versionierten Dateien. README
beschreibt den aktuellen Vier-Dienste-Stack. PLANUNG.md, seine HTML-Fassung und
der ursprüngliche chat-service-Plan sind als Gesamtentwurf beziehungsweise historische
Vorlage gekennzeichnet. Sie bleiben als Quellen erhalten; der Auftrag verweist auf
das Datenmodell in PLANUNG.md. Lokale Markdown-Verweise lösen auf, alle neun
PowerShell-Blöcke der Spezifikation sind syntaktisch gültig. Build-Artefakte,
Unterrichtsunterlagen und lokale Zugangsdaten werden nicht versioniert.

Lokale Protokolle der ersten Nachprüfung: submission-s1.log (damals 113 Tests), submission-s2-build.log,
submission-s3-s7.log und submission-s3.json bis submission-s7.json in tempContext.

### Abschliessende Gegenprüfung vor der Lernphase

Bei der erneuten Durchsicht wurden keine weiteren Fehler im Anwendungscode
festgestellt. Zwei zuvor nicht direkt geprüfte Fehlerfenster sind jetzt als
Integrationstests mit echten Containern abgesichert:

- **Nach SQL, vor COMMIT:** Der Repository-Spy führt den echten JDBC-Batch aus
  und löst danach einen Fehler aus. Die Service-Transaktion rollt beide Inserts
  zurück. In einer separaten ignorierten Kopie ohne TransactionTemplate schlug
  derselbe Test erwartungsgemäss fehl: zwei gespeicherte Zeilen statt null.
  Damit erkennt der Test auch eine versehentlich entfernte Transaktionsgrenze.
- **Nach COMMIT, vor ACK:** Der Service-Spy speichert tatsächlich und schliesst
  dann einmal die echte RabbitMQ-Verbindung. Der Broker liefert erneut;
  mindestens zwei Service-Aufrufe führen zu genau einer unveränderten Zeile,
  anschliessend sind Schreibqueue und DLQ leer. Kein manueller Writer-Neustart.

Im S7-Integrationstest beginnt die 15-Sekunden-Ausfallzeit nun erst nach der
bestätigten Docker-Stop-Rückkehr. Vorher umfasste sie auch die Dauer des Stop-Befehls.
Die oben gemessene Compose-Abnahme nutzte bereits den korrekten Startpunkt.

Eine zusätzliche Prüfung mit dem Java-Parser fand Dokumentationskommentare für
alle **16 Klassen/Records und 92 Methoden/Konstruktoren** im Writer samt Tests.
Inhalt, Sprachregeln und Zuständigkeiten wurden zusätzlich gelesen; die reine
Anwesenheit eines Kommentars ersetzt diese Prüfung nicht. Lokale Dokumentlinks,
die neun PowerShell-Blöcke und die Ignore-Regeln wurden erneut kontrolliert.

Der abschliessende Root-Lauf mit allen 115 Tests steht in final-audit-s1-final.log.
Die gezielten Fehlerprüfungen, die absichtliche Gegenprobe ohne Transaktion und
die Kommentarprüfung stehen in final-audit-fault-windows.log,
final-audit-mutation.log und final-audit-comments.log, jeweils lokal in tempContext.
S2–S7 wurden in dieser Gegenprüfung nicht erneut ausgeführt: Anwendungscode,
Produktionskonfiguration, Dockerfiles und Abhängigkeiten sind gegenüber dem
bereits gemessenen Stand unverändert. Die zwei neuen Tests und die korrigierte
Testzeitmessung sind Ergänzungen zum bestehenden Nachweis.

Die folgenden Abschnitte dokumentieren die früheren Prüfungen vom 29.09.2026;
deren 99 Tests und alte Zeitwerte sind historische Ergebnisse.

## Historische Schlussabnahme vom 29.09.2026, Klon 4b55c19

Der lokale Klon wurde mit git clone --no-local erstellt: keine unversionierten
Dateien, keine übernommene .env und keine Build-Ergebnisse. .env wurde nur aus
.env.example kopiert. Der ursprüngliche Arbeitsstack wurde ohne Löschen seiner
Daten beendet. Der Prüfstack erhielt neue leere Volumes; Ausgangszustand:
0 Zeilen, beide Queues leer. Anschliessend S3–S7 in Reihenfolge, ohne Bereinigung.

| Szenario | Ergebnis der Schlussabnahme |
|---|---|
| S1 | mvn clean test vom Klon-Projektstamm: 99 Tests, 0 Fehler, 0 übersprungen |
| S2 | docker compose up -d --build erfolgreich; vier Dienste laufen, keine PortBindings; Tabelle und beide Indizes automatisch vorhanden |
| S3 | Alle 1000 IDs genau einmal; Queue leer; gesamte Sendung samt Prüfung 9,42 s |
| S4 | Rückstau 1000 ready/0 unacknowledged/0 Consumer; alle IDs gespeichert; Statistik 68 → 88, also 20 Transaktionen bei höchstens 100 erlaubt |
| S5 | Identischer JSON-Body zweimal ohne Java-Typheader: eine Zeile, alle sechs Felder unverändert, DLQ leer |
| S6 | Zwei Consumer; 1000 IDs ohne Duplikate; laut Logs je 500 Nachrichten verarbeitet |
| S7 | PostgreSQL nach 15 s gestartet; 300 Nachrichten während des Ausfalls in 3,34 s gesendet; alle IDs samt Schlussprüfungen nach 27,69 s; beide Writer-Prozesse unverändert |
| S8 | Keine Streams; deutsche Kommentare über Klassen und Methoden geprüft; englische Namen/Logs; lokale .env und tempContext ignoriert; Planungshistorie vorhanden |

Endzustand: **3301 Zeilen**, chat.persist und chat.dlq jeweils 0 ready und
0 unacknowledged. Bei S7 waren Container-ID, StartedAt und RestartCount beider
Writer vor/nach dem Ausfall identisch. Keine manuellen Writer-Neustarts bei S7.
Die abschliessende Dokumentation verändert weder Anwendungscode noch Prüflogik.

Danach wurde der Prüfstack ohne Löschen seiner Volumes beendet und der ursprüngliche
Arbeitsstack mit zwei Writern und seinen vorherigen Daten wieder gestartet.
Der veröffentlichte Abgabestand wird durch den Tag bewertung-1 gekennzeichnet.

Die folgenden Abschnitte erhalten die früheren Messwerte und Korrekturen aus Schritt 7.

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

## Vorprüfung aus Schritt 7

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

## Einordnung der Prüfungen

- Schritt 6: mvn clean test vom Projektstamm, 99 Tests ohne Fehler/Überspringen;
  Docker-Build, Schema/Index, keine Host-Portbindungen und Datenbestand nach
  Containerneuerstellung erfolgreich geprüft.
- Schritt 7: S3–S7 wie oben gemessen. Da nur Dokumentation und Prüfbefehle geändert
  wurden, wurden die unveränderten Java-Tests nicht erneut gestartet.
- Schritt 8: frischer lokaler Klon und vollständige eigene Abnahme wie oben;
  Lehrerauftrag, Code-Stil, Spezifikation und README abschliessend abgeglichen.
  Alle vier geforderten Abgaben sind vorhanden: Spezifikation, Plan, Code, Tests.
- Der Lernende reicht den Fork-Link selbst im Abgabeportal ein. Die Beurteilung
  durch das Lehrerskript und die Punktevergabe erfolgen durch die Lehrperson.
- Die Vorbereitung auf das spätere Code-Review-Gespräch folgt nach Fertigstellung
  des Abgabeprojekts. Die gemessenen Ergebnisse ersetzen dieses Gespräch nicht.
