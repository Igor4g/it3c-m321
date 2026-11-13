# Nachprüfung nach unabhängiger Review — Bewertung 2

> Der aktuelle Nachtrag N4 steht am Ende dieses Dokuments. Die vorangehenden
> Abschnitte dokumentieren die erste Korrekturrunde und ihren damaligen Stand.

Datum: 13.11.2026, Europe/Zurich. Ausgangspunkt ist die unabhängige Review von
`4cdcdb17674815723ec61aef732cf1ad232352ad`. Dessen grüne S1–S8 belegten weder den
verspäteten Cursor noch jede Stilvorgabe. Die erste Abnahme bleibt als historischer
Nachweis erhalten; diese Prüfung ergänzt die damals fehlenden Fälle.

Geprüfter korrigierter Code: `e325883449ae16ed0ea34ea09952edde060183ee`.
Vor der Umsetzung wurde der alte Plan mit Änderungsgrund ergänzt (`3e14ddc`).
Verhaltenskorrektur: `c9a413f`; Stilkorrektur: `e325883`. Die eigene Spezifikation
und die ursprünglichen Planabschnitte wurden nicht heimlich umgeschrieben.

## Korrekturen und Gründe

`StreamSession` behält den Cursor und bekannte Vorgänger über den Übergang zu
Live. Diese Ausschlüsse sind unabhängig vom rollenden Duplikatfenster der neuen
Nachrichten. Ein volles Live-Fenster kann den Cursor daher nicht vergessen lassen.
Die alten IDs stammen aus der RAM-Historie und einer begrenzten SQL-ID-Abfrage
desselben Raums. Insgesamt bleiben höchstens `LIVE_HISTORY_SIZE` IDs samt Cursor
erhalten, keine alten Nachrichtenkopien. Die Menge wächst nicht mit der Laufzeit.
Unbekannte, beliebig alte Duplikate ausserhalb dieses Fensters werden nicht als
global gelöst behauptet. Eine bisher unbekannte ID wird nicht allein wegen einer
älteren Uhrzeit verworfen.

Nur die vorbereitete SQL-/RAM-Historie wird nach Zeit und UUID sortiert. Während
des Ladens eintreffende Ereignisse werden danach in Empfangsreihenfolge angehängt.
ID-Abgleich entfernt Überschneidungen, anschliessend läuft Live über denselben
Ausgabeweg. Neue ältere Ereignisse überholen die vorbereitete Historie nicht mehr.
Damit entspricht der Ablauf Abschnitt 5 der eigenen Spezifikation.

Die SQL-Vorbereitung verwendet einen kurzen, nur lesenden Repeatable-Read-
Lesestand. `SELECT 1` legt ihn vor möglichen Tabellen-Wartezeiten fest. Später neu
gespeicherte IDs werden nicht rückwirkend zu alten Vorgängern erklärt. Die
Transaktion endet vor Öffnen des SSE-Streams. Dieses Verhalten folgt der
[PostgreSQL-16-Dokumentation](https://www.postgresql.org/docs/16/transaction-iso.html#XACT-REPEATABLE-READ).
Das in [Spring dokumentierte TransactionTemplate](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/programmatic.html)
grenzt diese Lesevorbereitung ab; auch Fehler beim Transaktionsbeginn werden 503.
Live-Consumer und reine Live-Verbindungen beginnen keine SQL-Transaktion.

Verschachtelte Argumente und Empfängerketten wurden im gesamten Java-Code des
Chat-Service samt Vorlagentests durch benannte Zwischenschritte ersetzt.
Wiederholte Testfristen sind gemeinsam benannt. Registrierung und Replay-Übergabe
besitzen kurze eigene Methoden. Die Anwendung erhält keine neuen Produktmodule,
Oberflächen oder Vorrats-Interfaces.

CodeStyleTest prüft Kommentare, Java-Streams und verschachtelte Aufrufe. Vier
negative Parser-Beispiele und ein positives Lambda-Beispiel prüfen den Prüfer.
Lambda-Körper zählen nicht als sofortige Argumentauswertung; ihre eigenen
Anweisungen werden trotzdem geprüft. Sprache, Zweckbegründungen und Lesbarkeit
bleiben ergänzende manuelle Kriterien. Syntaxgrün beweist nicht alle Stilregeln.
Dies sind technische KI-Prüfnotizen, keine erfundene Bestätigung einer persönlichen
Prüfung oder mündlichen Leistung des Lernenden.

## Regressionen und vollständige Abnahme

Der neue reale HTTP-/RabbitMQ-/PostgreSQL-Test startet mit leerem Instanzpuffer,
SQL-Vorgänger, Cursor und fehlender Nachricht. Er erhält die fehlende Nachricht
einmal; spätere Kopien aller drei IDs bleiben aus. Eine neue ältere ID kommt an.
Ein Sitzungstest mit Kapazität 1 prüft den Cursor nach Verschieben des Live-Fensters.
Der reale Tabellensperrtest prüft Geschichte vor FIFO-Übergabe einschliesslich
eines während des Ladens empfangenen und danach gespeicherten älteren Ereignisses.

Ein neuer Git-Klon des Prüfcommits verwendet eine bytegleiche Kopie von
`.env.example` als `.env`. S1: **140 Tests, 0 Fehler, 0 übersprungen**; BUILD SUCCESS
um 18:52:09, Dauer 3:28 Minuten. Die XML-Berichte bestätigen 39 Chat-Service- und
101 unveränderte Writer-Tests.

S2–S8 wurden anschliessend in Reihenfolge auf demselben isolierten Stack ausgeführt.
Nur dessen tatsächlicher Netzname wird durch eine externe Compose-Ergänzung auf
`m321-b2-correction-dfa3e5eb` gesetzt. Eine externe Kopie des PowerShell-Treibers
passt Wurzel-/Clientpfad und Netzwerknamen an. Node-Client, Java-Code, Dockerfiles,
sonstige Konfiguration und die Testdatenvergleiche bleiben unverändert.
Die Originalkonfiguration mit Netzname `chat-net` wurde separat kontrolliert:
kein Host-Port, kein fester Chat-Containername. Alle vier persönlichen Container
haben nach der Prüfung dieselben IDs und Startzeiten wie davor; sie wurden weder
ersetzt noch neu gestartet.
Dies ist keine Behauptung eines unmodifizierten oder bekannten Lehrerskripts.

| Szenario | Ergebnis der korrigierten Abnahme |
|---|---|
| S1 | 140 Tests erfolgreich in einem Lauf, nichts übersprungen |
| S2 | Vier Dienste, keine veröffentlichten Host-Ports; frischer Build/Start in 38.864 s |
| S3 | 400/400/0; vollständige IDs und Inhalte, einmalig, Absenderfolge korrekt |
| S4 | Zwei Queues mit je einem Consumer auf eine in 4.774 s; Persistenz-Queues erhalten |
| S5 | Alle 100 live in 513 ms ab erstem POST bzw. 11 ms ab letztem POST; danach dieselben 100 IDs gespeichert |
| S6 | Alle 5000 an aktivem Client; ready + unacked maximal 1000; neue Zustellung nach Fortsetzen |
| S7 | Nach 10 s genau 50 fehlende Nachrichten ohne Duplikate, danach eine neue Live-Nachricht |
| S8 | Erweiterter Syntaxcheck grün; .env und tempContext nicht versioniert |

Alle sieben Compose-Ergebnisse: `Passed: true`, Gesamtlauf Exit-Code 0.
Messräume: S5 `09dbaba7-e036-4168-a2e6-5e57f1d51025`,
S6 `3c5eff05-7f5f-4d45-a258-f62db048fbf6`,
S7 `fad4d479-1793-4462-909e-e10bb7fe8b85`.
Neue S6-ID: `15f38b9e-f29a-4095-9f6b-71773f1de8a2`.
S7-Cursor: `66ff39fd-d457-4072-91b5-3ae27caa92fe`.
Vollständige ID-Abgleiche erfolgen im Client; lokale Messartefakte liegen unter
`chat-service/target/acceptance/`. Queue-Stichproben ersetzen keine lückenlose
Messung; `999 ready + prefetch 1` begrenzt die Summe auch zwischen den Messpunkten.
Nur der eigene Prüfstack wird anschliessend ohne `-v` beendet.

## Veröffentlichung

Zum Erstellen dieses Nachweises wird der veröffentlichte `bewertung-2` nicht
verschoben; er bezeichnet weiter `4cdcdb1`. Eine Neuveröffentlichung erfordert
die ausdrückliche Zustimmung zum Ersetzen dieses bereits veröffentlichten Tags.
Neue lokale Commits allein korrigieren die eingereichte Tag-Version nicht.
`bewertung-1` bleibt unverändert. Das spätere persönliche Code-Review umfasst
weiterhin 16 Punkte und wird nicht durch diese Tests vorweggenommen.

Veröffentlichungsnachtrag vom 13.11.2026: Nach ausdrücklicher Freigabe des Lernenden
wurden `main` und `bewertung-2` auf `e4b5265` aktualisiert und ihre Remote-SHAs
verglichen. Die Angabe `4cdcdb1` oben beschreibt den Stand bei Erstellung des
ersten Nachweises. Eine weitere Korrektur benötigt eine neue Tag-Freigabe.

## Nachtrag N4 — Gespeicherte beobachtete Nachfolger, 13.11.2026

Die unabhängige erneute Prüfung von `e4b5265` fand eine reale Regression der ersten
Korrektur: Der RAM enthält `C → M`, beide Nachrichten stehen in PostgreSQL, aber
M besitzt eine ältere Uhrzeit. SQL ordnet M vor C ein; die neue Ausschlussmenge
verwarf dadurch einen tatsächlich beobachteten Nachfolger. Die bisherigen Tests
prüften ältere neue Ereignisse während oder nach dem Nachladen, nicht diesen
bereits vor der Wiederverbindung gespeicherten Fall.

Die Korrektur ermittelt aus der vorhandenen RAM-Kopie die IDs desselben Raums,
die tatsächlich nach dem Cursor empfangen wurden. Solche IDs werden nicht als
SQL-Vorgänger ausgeschlossen. Die beobachtete Position hat Vorrang vor dem
Zeit-/UUID-Vergleich. Ist der Cursor nicht im RAM vorhanden, entsteht keine solche
Ausnahme; das bisherige Verhalten für kalte Instanzen bleibt bestehen. Cursor und
echte bekannte Vorgänger bleiben geschützt. Die zusätzliche Hilfsmenge lebt nur
während der Vorbereitung und ist durch die vorhandene Historiengrenze beschränkt.
Es gibt keine neue SQL-Abfrage, Konfiguration oder Framework-Abhängigkeit.

Der neue parametrisierte reale HTTP-/RabbitMQ-/PostgreSQL-Test war vor der
Korrektur zweimal rot: Statt M kam nur die neue Live-Markierung. Nach der
Korrektur erhält er M und danach die Markierung genau einmal. Er prüft sowohl
ältere Uhrzeit als auch gleiche Uhrzeit mit kleinerer UUID. Zusätzlich bleibt ein
echter RAM-Vorgänger mit neuerer Uhr ausgeschlossen. Späte Kopien von Vorgänger,
Cursor und fehlender Nachricht erzeugen keine weiteren Ereignisse. Zusammen mit
den bisherigen kalten Cursor-, Übergabe-, Sitzungs- und Stilfällen sind 17 gezielte
Tests grün, keine übersprungenen Tests.

Geprüfter Code: `3a5c46322adf94833db70bd6f35fbb65cd08eaab`, neuer Git-Klon,
`.env` bytegleich mit `.env.example`. Vollständiger Maven-Lauf: **142 Tests,
0 Fehler, 0 übersprungen**, BUILD SUCCESS um 22:14:03, Dauer 3:24 Minuten.
Die XML-Berichte bestätigen 41 Chat-Service- und 101 Writer-Tests.

Danach S2–S8 in Reihenfolge auf demselben neuen isolierten Stack, Exit-Code 0:

| Szenario | N4-Ergebnis |
|---|---|
| S2 | Vier Dienste, keine Host-Ports; Build/Start 38.078 s |
| S3 | 400/400/0; exakte IDs und Inhalte, einmalig, Absenderfolge korrekt |
| S4 | Zwei Live-Queues auf eine in 6.340 s; Persistenz-Queues erhalten |
| S5 | 100 live in 404 ms ab erstem POST bzw. 10 ms ab letztem POST; danach genau diese 100 IDs gespeichert |
| S6 | Alle 5000 zugestellt; ready + unacked maximal 1000; neue Zustellung nach Fortsetzen |
| S7 | Nach 10 s genau 50 fehlende Nachrichten, danach neue Live-Nachricht |
| S8 | Erweiterte Stilprüfung grün, private Dateien nicht versioniert |

Netzname der isolierten Messung: `m321-b2-successor-85a4c934`; nur externe
Compose-Netzergänzung und Treiberpfade/-netzname weichen ab. Node-Client,
Anwendung, Dockerfiles und Testvergleiche stammen unverändert aus dem Prüfcommit.
Originales `chat-net`, fehlende Host-Ports und Skalierbarkeit wurden separat
geprüft. Die vier persönlichen Container besitzen weiterhin dieselben IDs und
Startzeiten. Nur der eigene Prüfstack wurde ohne Volumenlöschung beendet.

Messräume: S5 `d4357b01-43bf-4ce8-8e48-a0ef422cff6e`,
S6 `7e47eb09-a915-4396-b840-5467c83a9541`,
S7 `ef14a55d-b73e-4986-975a-b67cde320f03`.
Neue S6-ID: `94c8af03-001a-40dd-a684-6dec45302529`.
S7-Cursor: `cab29033-97ce-4940-a387-870f1ed69e54`.

Nach dieser Prüfung werden nur Dokumentation und Prüfnotizen ergänzt. Der
veröffentlichte Tag bleibt beim Erstellen dieses Nachtrags auf `e4b5265`, bis
eine weitere ausdrückliche Tag-Freigabe vorliegt. Eigene Spezifikation und alte
Planabschnitte bleiben erhalten. Die Ergebnisse belegen diese Prüfungen;
Lehrerskript und mündliche Punkte werden damit nicht vorab garantiert.
