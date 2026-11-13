# Umsetzungsplan — Live-Zustellung

**Vom Lernenden am 13.11.2026 freigegebener Plan vor Implementierungsbeginn.**

Grundlage ist die vom Lernenden verfasste
[Spezifikation](spec-live-zustellung.md). Dieser Plan wurde mit KI-Unterstützung
erstellt. Es hat noch keine Implementierung der Live-Zustellung begonnen.

## 1. Voraussetzungen vor dem ersten Implementierungsschritt

DB-Entscheidung des Lernenden am 13.11.2026: Wiederaufnahme braucht eine erreichbare
DB; bei DB-Ausfall HTTP 503. Live ohne Header bleibt davon unabhängig.

Vor dem ersten Implementierungsschritt werden diese Handlungen in Reihenfolge
ausgeführt: die technische Prüffassung und die eigene Spezifikation gemeinsam
prüfen, die Dokumente vom Lernenden freigeben lassen, sie committen und nach
ausdrücklicher Zustimmung auf `main` pushen. Danach auf GitHub den veröffentlichten
Commit prüfen. Dessen Kennung wird im Feld `Geprüft und korrigiert` von Schritt 1
als Beleg für den vorausgehenden Plan-Push festgehalten.

Diese Reihenfolge ist Teil des festen Plans. Nach der Veröffentlichung werden hier
keine Freigabe-Checkboxen nachgetragen; laufende Nachweise gehören ausschliesslich
in die dafür vorgesehenen Prüfnotizen.

Bis dahin sind die folgenden Schritte Vorschläge zur Prüfung. Die genannten neuen
Testklassen und Prüfskripte **existieren noch nicht**. Ihre Befehle beschreiben die
geplanten Prüfeinstiege und sind keine Behauptung bereits erfolgreicher Tests.

## 2. Arbeitsweise nach Veröffentlichung

Jeder Schritt umfasst ein Thema, seinen Test und einen eigenen Commit. Der Lernende
erteilt den unten wörtlich festgehaltenen KI-Auftrag, liest die Änderungen und prüft
das Ergebnis. Erst danach wird committet. Das Feld `Geprüft und korrigiert` wird mit
dem tatsächlichen Test, Ergebnis und den angenommenen oder verworfenen Änderungen
ergänzt. Die KI kann bei der Prüfung helfen; eine Prüfung des Lernenden wird nicht
erfunden.

Commit-Texte sind deutsch. Commits mit KI-Beiträgen enthalten eine passende
`Co-Authored-By: Codex <codex@localhost>`-Zeile. Das ist eine lokale technische
Kennung, kein behauptetes offizielles E-Mail-Konto. Neue Variablen stehen in `.env.example`, nicht in Git
versionierten Geheimnissen. `CLAUDE.md` gilt für Produktions- und Testcode.

Nach der Veröffentlichung bleibt der ursprüngliche Plan stehen. Umplanungen
werden vor ihrer Umsetzung als eigener Eintrag mit `Änderungsgrund` in einem
eigenen Commit ergänzt. Ausnahme: das vorgesehene Feld `Geprüft und korrigiert`.
Pushes, Tags und die Abgabe erfolgen nur mit der Zustimmung des Lernenden.

## 3. Technische Leitplanken

- Bestehendes Spring MVC mit `SseEmitter` erweitern, keine neue Oberfläche und
  kein WebFlux, Gateway, Login-System oder neues Produktionsmodul.
- HTTP-Routing über einen `StreamController`; Raum- und Verbindungsverwaltung
  über einen `StreamService`; eine verständliche `StreamSession` pro Verbindung.
- Der Compose-Dienst bleibt `chat-service` und hat kein `container_name`.
  Kein Dienst veröffentlicht einen Host-Port. Tests erreichen jede Instanz
  einzeln innerhalb von `chat-net`.
- Genau eine temporäre, exklusive, nicht dauerhafte, automatisch entfernte Queue
  je Instanz an `chat.delivery`; genau ein Consumer, `prefetch=1`, maximal
  999 wartende Nachrichten, Überlauf mit `drop-head`.
- Spring-Listener bestätigt nach erfolgreicher lokaler Übernahme. Das ist kein
  Beweis der Anzeige beim Client. Die bestehende Persistenz-Queue bleibt erhalten.
- Ein geordneter Ausgabeweg und begrenzter Puffer je Client. Kein Netzwerk-Schreiben
  unter einer gemeinsamen Sperre oder direkt als blockierende Arbeit im Consumer.
- PostgreSQL ausschliesslich für den neuen Lesepfad anbinden. Live darf weder
  beim Start noch bei späteren Ausfällen auf eine verfügbare DB angewiesen sein.
  Wiederaufnahme mit `Last-Event-ID` braucht dagegen eine erfolgreiche DB-Abfrage.
- Höchstens 10'000 Nachrichten in der jüngsten Instanz-Historie, im Puffer einer
  Client-Verbindung und je Nachlieferung. Keine unbegrenzt wachsenden ID-Sets.
- Heartbeats als SSE-Kommentare ohne Nachrichten-ID und ohne Chat-`data` planen,
  damit sie nicht als zusätzliche Nachrichten in die Szenariozählung eingehen.
- UUIDs nicht als zeitliche Reihenfolge interpretieren. Für Geschichte und
  Übergabe gilt das unten festgelegte Modell; Live-Ausgaben bleiben pro Client FIFO.
- Keine globale Exactly-once-Garantie behaupten. Die verlangten Mengen,
  Duplikatfreiheit und Reihenfolgen werden in den konkreten Szenarien nachgewiesen.

### 3.1 Reihenfolge und Zeitstempel

Die verlangte Reihenfolge eines Absenders beginnt bei dessen nacheinander
angenommenen POSTs. Konkurrierende Anfragen verschiedener Absender benötigen
keine gemeinsame globale Reihenfolge. Ein Raum- oder instanzübergreifender
zentraler Sequenzdienst wird nicht eingeführt.

In jeder Instanz werden Identitäts-/Zeitvergabe und Veröffentlichung eines POSTs
in einem kurzen synchronisierten Annahmeweg ausgeführt. Neue `sentAt`-Werte haben
Mikrosekundenpräzision und steigen innerhalb dieser Instanz strikt: bei gleicher
oder rückläufiger Uhrzeit wird eine Mikrosekunde auf den vorherigen Wert addiert.
Damit gehen Reihenfolge und Zeitpräzision beim Speichern nicht allein durch
Nanosekundenrundung verloren. Ein Test mit gleicher und rückläufiger Uhr prüft das.

Der Publisher verwendet die bereits vorgesehene getrennte Publisher-Verbindung,
deren Kanal-Cache auf einen Kanal mit endlichem Checkout-Timeout begrenzt wird.
Consumer teilen diese Begrenzung nicht. Die beiden vorhandenen Publikationen
bleiben in der Reihenfolge Persistenz, dann Fanout; sie sind keine atomare
Transaktion über beide Ziele.

Für die nachzuliefernde Historie wird nach `sentAt` aufsteigend sortiert, bei
Gleichstand nach der kanonischen UUID-Zeichenfolge. Gleiche Werte werden damit
deterministisch behandelt; die UUID allein ist kein Zeitstempel. SQL und Java
müssen denselben Vergleich benutzen. Alte gleiche Zeitstempel erlauben keine
Rekonstruktion einer ursprünglich nicht gespeicherten Reihenfolge. Insbesondere
wird keine globale Sendereihenfolge konkurrierender Instanzen versprochen.

Live-Ereignisse gehen in der Empfangsreihenfolge des einzigen Queue-Consumers an
den einzigen Ausgabeweg jeder Sitzung. Eine Kopie wird nie zusätzlich direkt vom
POST-Handler an SSE gesendet: sonst käme sie über den Fanout ein zweites Mal an.

### 3.2 Wiederaufnahme und Übergabe

1. UUID-Parameter prüfen. Ohne Header die Sitzung unter der lokalen
   Koordinationssperre registrieren und live starten, ohne JDBC aufzurufen.
2. Mit Header die Sitzung zuerst als nachladend registrieren. Den jüngsten
   Nachrichtenpuffer unter derselben Sperre kopieren. Neue Ereignisse werden
   für diese Sitzung ab jetzt begrenzt gesammelt.
3. Ausserhalb der Sperre PostgreSQL lesen. Bei technischem DB-Fehler 503 und die
   vorbereitete Sitzung entfernen. Der RAM-Puffer ersetzt diesen DB-Zugriff nicht.
4. Den Cursor anhand seiner ID in DB oder aktueller Historie suchen und den Raum
   prüfen. Nach erfolgreichem DB-Zugriff bedeutet unbekannt/raumfremd 404. Ein
   nur in RAM vorhandener Cursor ist zulässig: der Writer kann verzögert sein.
5. Den DB-Raumverlauf nach dem Cursor anhand des vereinbarten Vergleichs lesen;
   maximal Limit plus eins lesen, damit Abschneiden erkennbar bleibt. Ist der
   Cursor in der jüngsten RAM-Historie enthalten, dessen tatsächliche Position
   benutzen: dort bereits vor dem Cursor empfangene IDs werden nicht durch eine
   DB-Überschneidung erneut nachgeliefert. Jüngste tatsächliche Nachfolger des
   Cursors bleiben Kandidaten, auch bei gleichen oder abweichenden Zeitstempeln.
6. Unter der lokalen Sperre DB-Ergebnis, kopierte jüngste Nachrichten und inzwischen
   gesammelte Ereignisse anhand ihrer ID zusammenführen. Nur den gewählten Raum
   übernehmen und die Cursor-ID ausschliessen. Während der Registrierung neu
   eingetroffene Ereignisse nicht allein wegen `sentAt <= Cursor-Zeit` verwerfen:
   ein später empfangenes Ereignis kann einen älteren Zeitstempel haben. Den
   Historienvergleich und die beobachtete Absenderfolge beachten und die Grenzen
   prüfen. Kein SQL und kein SSE-Schreiben innerhalb dieser Sperre.
7. Die vorbereitete Folge in den geordneten Sitzungspuffer übergeben und die Sitzung
   unter derselben Sperre auf Live umschalten. Neu eintreffende Ereignisse werden
   danach hinter der vorbereiteten Folge eingereiht. Bei Überlappungen mit bereits
   nachgeladenen IDs werden sie innerhalb des begrenzten Abgleichs nicht verdoppelt.
8. Erst nach erfolgreicher Vorbereitung den SSE-Stream öffnen. Ein Ausgabe-Worker
   pro Sitzung sendet die vorbereitete Folge und spätere Live-Ereignisse. Vor dem
   ersten HTTP-Stream kann ein Fehlerstatus zurückgegeben werden, danach wird bei
   Fehlern nur die betroffene Sitzung beendet.

Die gemeinsame Sperre schützt nur die lokale Registrierung, Pufferübergabe und
Zustandsänderung. Sie ist keine verteilte Transaktion. Abgleichsdaten und Puffer
sind begrenzt. Unterbrechungen, bei denen Nachrichten weder in der DB noch im
jüngsten Puffer vorhanden sind, können nicht allgemein rekonstruiert werden.
Erkennbare Lücken oder überschrittene Grenzen werden nicht als vollständige
Nachlieferung ausgegeben. S7 sowie Datenbankverzögerung und gleichzeitiger
Live-Eingang müssen den tatsächlichen Ablauf mit realen Diensten nachweisen.

### 3.3 Grenzen, Fehler und Betrieb

- Fehlendes/ungültiges `roomId`, ungültiger Header: HTTP 400.
- Unbekannter oder raumfremder Cursor nach erfolgreicher DB-Prüfung: HTTP 404.
- Überschrittene Nachlieferungsgrenze oder erkannte nicht überbrückbare Lücke:
  HTTP 409 vor Beginn des Streams. Abbrechen statt einen Teil als vollständig senden.
- Datenbank bei Wiederaufnahme nicht erreichbar: HTTP 503, auch wenn RAM-Daten
  vorhanden sind. Bestehende und neue reine Live-Verbindungen bleiben unabhängig.
- Je Instanz-Historie, Sitzungspuffer und Nachlieferung: 10'000 Nachrichten.
  Konfiguration über `LIVE_HISTORY_SIZE`, `LIVE_CLIENT_BUFFER_SIZE`,
  `LIVE_REPLAY_LIMIT` mit diesen Standardwerten in `.env.example`.
- Heartbeat alle 15 Sekunden als SSE-Kommentar, ohne Chat-ID oder Chat-Daten.
- Direkt nach erfolgreicher Registrierung/Vorbereitung einen ersten Kommentar
  senden, damit HTTP-Header und Stream sofort sichtbar werden. Testclients müssen
  nicht bis zum ersten Chat-Ereignis oder dem ersten 15-Sekunden-Heartbeat warten.
- Ersten Stream erst freigeben, wenn eigene Queue und Consumer verfügbar sind.
  Timeouts, IOException und Abschluss räumen die Sitzung auf.
- JDBC-Verbindung mit kurzem Connect-/Socket-/Pool-Timeout; kein blockierender
  Pool-Start oder SQL-Init. Compose startet den Chat-Service weiterhin unabhängig
  von einer gesunden PostgreSQL; keine zusätzliche DB-Startabhängigkeit.
- Bei einer einzelnen abgebrochenen/überfüllten Sitzung nicht auf deren
  Netzwerk-Schreibvorgang warten und keine anderen Verbindungen unterbrechen.
- Connection-Recovery deklariert Queue und Bindung erneut und startet den Consumer
  wieder. Insbesondere darf nach S6 kein manueller Service-Neustart nötig sein.

### 3.4 Nachweise und technische Quellen

- [Spring AMQP: strikte Publisher-Reihenfolge](https://docs.spring.io/spring-amqp/reference/amqp/template.html)
  beschreibt einen auf einen Kanal begrenzten Publisher-Cache samt Checkout-Timeout
  und die Trennung von Consumer-Verbindungen. Die benötigten Methoden wurden vor
  Implementierungsbeginn am lokalen Spring-Rabbit-JAR **3.2.12** per `javap` geprüft.
- [PostgreSQL 16: Zeittypen](https://www.postgresql.org/docs/16/datatype-datetime.html)
  dokumentiert Mikrosekundenpräzision für `timestamptz`.
- [HTTP 409](https://www.rfc-editor.org/rfc/rfc9110.html#name-409-conflict)
  ist unsere Entscheidung für eine nicht erfüllbare Nachlieferung im aktuellen
  Zustand; der Bewertungsauftrag schreibt diesen Statuscode nicht vor.
- Java 21.0.9 und Maven 3.9.9 sind lokal vorhanden. Maven liegt in der vorhandenen
  IntelliJ-Installation; für Prüfungen dessen `bin` und Java 21 in den Prozess-PATH
  aufnehmen. Kein globales Umstellen der Entwicklungsumgebung erforderlich.

Neue Tests für diese Entscheidungen: gleiche Zeitstempel und Uhrenrücksprung,
deterministische ID-Gleichstände, parallele POSTs innerhalb einer Instanz,
noch nicht gespeicherter Cursor, Überlappung DB/RAM, Ereignisse während der
Übergabe, 10'001 statt 10'000 Nachrichten, unbekannter/fremder Cursor und DB-Ausfall.
Zusätzlich einen während des Nachladens eintreffenden fremden Absender mit älterem
Zeitstempel prüfen: dessen neue Nachricht darf nicht weggefiltert werden. Ein im
DB-Ergebnis enthaltener, erst später vom Consumer empfangener Datensatz darf im
Übergang nicht doppelt erscheinen. Erfolgreich beendete Streams dürfen keine
Sitzung oder Ausgabeaufgabe zurücklassen.

## 4. Schritte und KI-Aufträge

### Schritt 1 — Instanz-Queue und Lebensdauer

**Ziel und Reihenfolge:** Zuerst die Grundlage des Fanout messbar machen, bevor
Clients angeschlossen werden. Bestehende Queues und JSON-Verträge erhalten.

**Auftrag an die KI im Wortlaut:**

> Implementiere ausschliesslich die Instanz-Queue aus unserer freigegebenen
> Spezifikation. Erweitere die vorhandene RabbitConfig um die temporäre Queue und
> ihre Bindung an chat.delivery. Verwende die vereinbarten Grenzen. Ergänze einen
> echten RabbitMQ-Test mit zwei getrennten App-Instanzen und dem Entfernen einer
> Instanz. Beachte CLAUDE.md. Implementiere noch keine SSE-Schnittstelle. Führe den
> angegebenen Test aus und berichte über Änderungen und Ergebnis. Committe und
> pushe nichts vor der Prüfung des Lernenden.

**Test:** `mvn -pl chat-service test "-Dtest=LiveQueueIntegrationTest"`.
Prüfen: zwei getrennte Queues, richtige Bindungen/Argumente, Eigentum und Entfernung
beim Schliessen einer Instanz; keine Änderung an `chat.persist`/`chat.dlq`.
Die Prüfung von genau einem aktiven Consumer wird mit Schritt 2 vervollständigt.

**Geprüft und korrigiert:** 13.11.2026, KI-Selbstprüfung mit dem angegebenen
Maven-Befehl: 1 Test, 0 Fehler, 0 übersprungen; echter RabbitMQ und zwei Spring-
Kontexte. Queue-Eigenschaften, tatsächliche Bindungen und Entfernen nach Schliessen
geprüft. Korrigiert: dynamischen Testcontainer-Port als Kommandozeilen-Eigenschaft
statt niedrig priorisierter Default-Property übergeben. Docker Desktop war zuerst
ausgeschaltet und wurde gestartet; kein Test wurde deshalb deaktiviert.
Vorgezogener Plan-Push: `27f50377136778096ec592ffe2b750697440c956`, mit GitHub-main
vor dem ersten Code-Patch verglichen. Der Gesamtauftrag wurde vom Lernenden
freigegeben. Ergänzung am 13.11.2026: Der Lernende hat den persönlichen Review
und die Annahme der Schritte 1 und 2 im Gespräch bestätigt.

### Schritt 2 — SSE, Raumfilter und Consumer

**Ziel und Reihenfolge:** Nun den kleinsten durchgehenden Live-Weg bauen, auf dem
anschliessend Skalierung und Fehlerfälle getestet werden können.

**Auftrag an die KI im Wortlaut:**

> Implementiere den Live-Weg aus der freigegebenen Spezifikation: einen Consumer
> je Instanz-Queue, StreamController für GET /stream?roomId, Raumzuordnung und
> Verbindungsverwaltung. Nutze SseEmitter und das bestehende Nachrichtenformat.
> Gib jede Chat-Nachricht mit id und vollständigem JSON als data aus. Halte den
> Live-Weg unabhängig von PostgreSQL. Ergänze echte HTTP-/RabbitMQ-Tests für
> Empfang, Raumfilter, Header und Aufräumen. Sende sofort einen anfänglichen
> SSE-Kommentar, damit die Verbindung vor dem ersten Chat-Ereignis bereit ist.
> Beachte CLAUDE.md. Implementiere noch
> keine Nachlieferung. Führe den Test aus und berichte. Kein Commit oder Push vor
> der Prüfung des Lernenden.

**Test:** `mvn -pl chat-service test "-Dtest=StreamIntegrationTest,LiveQueueIntegrationTest"`.
Prüfen: echtes HTTP-SSE statt nur MockMvc, korrekte ID/JSON, kein fremder Raum,
ein Consumer pro Queue und Aufräumen bei Abschluss/Fehler. Den ersten Kommentar
ohne Chat-ID/Chat-Daten empfangen können, bevor die erste Nachricht gesendet wird.

**Geprüft und korrigiert:** 13.11.2026, KI-Selbstprüfung: 4 echte HTTP-/RabbitMQ-
Tests grün, 0 Fehler und 0 übersprungen. Unicode/Zeilenumbruch und alle sechs
Felder, fremder Raum, ungültige UUIDs, genau ein Consumer und Aufräumen geprüft.
Korrigiert: `server.shutdown=immediate`, weil ein Graceful Shutdown auf absichtlich
offene SSE-Anfragen wartete. Der Abbruchtest erzeugt normale weitere Schreibversuche;
ein TCP-Abbruch ist nicht zwingend vor dem nächsten Schreiben sichtbar.
Eigene Sitzungsausgabe und Testclient sind getrennt. Ergänzung am 13.11.2026:
Der Lernende hat den persönlichen Review und die Annahme dieses Schritts bestätigt.

### Schritt 3a — Geordnete Annahme und Veröffentlichung

**Ziel und Reihenfolge:** Vor der Verteilung an zwei Instanzen den vorhandenen
Sendeweg absichern. Dieser Schritt verändert nur Zeitvergabe und Veröffentlichung;
Client-Ausgabe und Skalierung werden danach getrennt geprüft.

**Auftrag an die KI im Wortlaut:**

> Setze ausschliesslich die Mikrosekunden-Zeitvergabe, die geordnete Annahme und
> den einzelnen Publisher-Kanal aus Abschnitt 3.1 um. Behalte den JSON-Vertrag,
> POST /messages und beide Veröffentlichungsziele bei. Verwende keine zentrale
> Sequenz-Datenbank und keine RabbitMQ-Transaktion für beide Ziele. Prüfe gleiche
> und rückläufige Uhrzeit, den Publishing-Weg sowie die bestehenden Annahmetests.
> Beachte CLAUDE.md. Führe die Tests aus und erkläre die Änderungen am Bestand.
> Kein Commit oder Push vor der Prüfung des Lernenden.

**Test:** `mvn -pl chat-service test "-Dtest=MessageServiceTest,MessageTimestampTest,MessagePublisherIntegrationTest"`.
Prüfen: streng steigende Mikrosekundenwerte, gleiche IDs/Nutzdaten in beiden
Publikationswegen und Erhalt der bisherigen 202-Annahme. Synchronisierung und
Kanalbegrenzung dürfen den Consumer nicht blockieren.

**Geprüft und korrigiert:** 13.11.2026, KI-Selbstprüfung: 7 Tests grün, keine Fehler
oder übersprungenen Tests. Gleiche/rückläufige Uhr und beide Veröffentlichungswege
mit echtem RabbitMQ geprüft. Korrigiert: Die exklusive Queue im bestehenden
Publisher-Test über die Consumer-Verbindung lesen, die sie angelegt hat; die neue
Publisher-Verbindung darf diese exklusive Queue nicht konsumieren. JSON-Konverter
und bisherige POST-Annahme erhalten. Der Lernende hat den persönlichen Review und
die Annahme der Schritte 3a und 3b am 13.11.2026 bestätigt.

### Schritt 3b — Begrenzte Client-Ausgabe und Zwei-Instanzen-Test

**Ziel und Reihenfolge:** Auf dem abgesicherten Sendeweg jetzt langsame Clients
isolieren und die tatsächliche Verteilung an zwei Instanzen nachweisen.

**Auftrag an die KI im Wortlaut:**

> Vervollständige die geordnete und begrenzte Ausgabe je Verbindung. Ein langsamer
> oder abgebrochener Client darf keinen anderen blockieren. Ergänze S3 mit zwei
> tatsächlichen App-Instanzen, drei Clients und je 200 POSTs pro Instanz.
> Vergleiche IDs, vollständige JSON-Inhalte und Absenderreihenfolge. Verwende
> keine gemeinsam gemockte Verteilung. Prüfe den Überlauf einer Sitzung und die
> weitere Zustellung an einen gesunden Client. Beachte CLAUDE.md. Führe die Tests
> aus und berichte. Kein Commit oder Push vor der Prüfung des Lernenden.

**Test:** `mvn -pl chat-service test "-Dtest=LiveDistributionIntegrationTest,StreamSessionTest"`.
Prüfen: 400 IDs an beiden A-Clients, keine doppelten IDs, B bleibt leer; FIFO und
Überlauf eines einzelnen Client-Puffers mit unverändert weiterarbeitendem anderen
Client. Keine zufälligen langen Wartezeiten als Ersatz für Bereitschaftssignale.

**Geprüft und korrigiert:** 13.11.2026, KI-Selbstprüfung: 3 Tests grün, keine Fehler
oder übersprungenen Tests. Zwei echte HTTP-Anwendungen und RabbitMQ liefern beide
200er-Sendeserien vollständig an beide Raum-A-Clients. Alle sechs Werte, exakte
ID-Mengen, Duplikate, Absenderfolge und leerer Raum B geprüft. Kontrolliert blockierter
Sitzungs-Worker hält einen anderen nicht auf; Pufferüberlauf meldet sich sofort.
HTTP-Testaufrufe begrenzt, damit ein Fehler nicht unbegrenzt wartet. Der Lernende
hat den persönlichen Review und die Annahme der Schritte 3a/3b am 13.11.2026 bestätigt.

### Schritt 4 — DB-Lesepfad und jüngste Nachrichten

**Ziel und Reihenfolge:** Die Quellen der Nachlieferung erst isoliert prüfen,
bevor sie mit einer offenen SSE-Verbindung koordiniert werden.

**Auftrag an die KI im Wortlaut:**

> Implementiere den vereinbarten PostgreSQL-Lesepfad und den begrenzten Puffer
> jüngster Nachrichten. Verwende JdbcTemplate und parametrisierte Abfragen,
> keine Schreibzugriffe des Chat-Service. Ergänze JDBC-Abhängigkeiten und
> Beispielkonfiguration nur soweit erforderlich. Stelle sicher, dass Start,
> POST und Live-Stream nicht auf eine erreichbare DB warten. Prüfe Cursor,
> Raumfilter, Grenzen und das freigegebene Ordnungsmodell mit echten PostgreSQL-
> Tests. Beachte CLAUDE.md. Führe die Tests aus und berichte. Kein Commit oder
> Push vor der Prüfung des Lernenden.

**Test:** `mvn -pl chat-service test "-Dtest=MessageHistoryIntegrationTest,RecentMessageBufferTest"`.
Prüfen: Cursor in DB bzw. nur im aktuellen Puffer, Ausschluss des Cursors,
fremder Raum, gleiche Zeitstempel, begrenzter Speicher und keine Änderungen an
bestehenden Nachrichtenwerten. Danach `mvn clean test` als Prüfung der betroffenen
bestehenden Spring-Kontexte und des Writers.

**Geprüft und korrigiert:** 13.11.2026, KI-Selbstprüfung: 3 gezielte Tests grün,
anschliessend `mvn clean test` im Wurzelprojekt vollständig grün einschliesslich
aller 101 bisherigen Writer-Tests. Originalschema des Writers als Testressource
verwendet, keine zweite abweichende Schema-Kopie. SQL-Cursor, Raumfilter,
Mikrosekunden und UUID-Gleichstände oberhalb des Vorzeichenbits real geprüft.
Jüngster Puffer bleibt begrenzt und erhält Empfangsreihenfolge. Compose bekommt
DB-Leseparameter, aber keine neue PostgreSQL-Startabhängigkeit des Chat-Service.
Der Lernende hat den persönlichen Review und die Annahme der Schritte 4/5
am 13.11.2026 im Gespräch bestätigt.

### Schritt 5 — Wiederaufnahme und atomarer Übergang zu Live

**Ziel und Reihenfolge:** Die einzeln geprüften Quellen jetzt ohne Lücke,
Duplikate oder Überholen durch neue Nachrichten verbinden.

**Auftrag an die KI im Wortlaut:**

> Implementiere Last-Event-ID gemäss unserem freigegebenen Wiederaufnahmemodell.
> Registriere neue Ereignisse schon während des Ladens der Historie. Führe DB-
> und Pufferdaten anhand der ID zusammen und übergib sie geordnet an denselben
> Ausgabeweg. Ergänze die vereinbarten Fehlerantworten und Grenzen. Implementiere
> S7 mit echtem HTTP, RabbitMQ und PostgreSQL sowie einen Test mit neuen Ereignissen
> während der Nachlieferung. Beachte CLAUDE.md. Führe die Tests aus und berichte
> über jedes erkannte Rennen. Kein Commit oder Push vor der Prüfung des Lernenden.

**Test:** `mvn -pl chat-service test "-Dtest=StreamReconnectIntegrationTest"`.
Prüfen: genau 50 nach 10 s, Cursor nicht erneut, danach Live; noch nicht gespeicherte
Nachrichten, Überschneidungen, Invalidität, DB-Fehler und neue Ereignisse während
der Nachlieferung. Ein verspätetes Ereignis mit älterem Zeitstempel darf nicht
allein deshalb fehlen; ein später vom Broker eintreffender bereits nachgeladener
Datensatz darf nicht doppelt erscheinen. Auch Wiederaufnahme an der anderen
Instanz bei verfügbarer Historie prüfen. Grenzen der zugesagten Wiederaufnahme
ausdrücklich prüfen.

**Geprüft und korrigiert:** 13.11.2026, KI-Selbstprüfung: 9 Tests mit echtem HTTP,
RabbitMQ und PostgreSQL grün, keine Fehler oder übersprungenen Tests. S7 mit genau
50 Ereignissen nach 10 s und Instanzwechsel; anschliessend neue Live-Zustellung.
SQL-Tabellensperre beweist Aufnahme neuer Ereignisse während des Ladens, inklusive
älterem Zeitstempel. Späte Broker-Kopie bereits gelesener SQL-Zeile bleibt einmalig.
400/404/409/503, noch nicht gespeicherter Cursor, DB-Ausfall ohne Live-Ausfall und
Pufferüberlauf geprüft. Korrigiert: PostgreSQL-Testcontainer erhält expliziten freien
Testport, weil Docker eine zufällige Zuordnung beim Stop/Start ändern konnte.
Die Testvorbereitung schreibt Historie direkt; den echten Writer ergänzt die
Compose-Abnahme. Der Lernende hat den persönlichen Review und die Annahme
der Schritte 4/5 am 13.11.2026 im Gespräch bestätigt.

### Schritt 6 — Dokumentation und Stil vollständig prüfen

**Ziel und Reihenfolge:** Nach der vollständigen Live-/Replay-Implementierung
Stilprüfung und Dokumentation abschliessen, damit die anschliessende vollständige
Compose-Abnahme S8 ohne fehlenden CodeStyleTest ausführen kann.

**Auftrag an die KI im Wortlaut:**

> Prüfe den gesamten Chat-Service gegen CLAUDE.md, einschliesslich vorhandener
> Klassen und Tests. Ergänze verständliche deutsche Zweckkommentare und
> vereinfache unnötige Abstraktionen. Ergänze einen CodeStyleTest für das
> Vorhandensein von Kommentaren und das Verbot von Java-Streams. Die tatsächliche
> deutsche Sprache und Lesbarkeit werden zusätzlich manuell geprüft.
> Aktualisiere die README-Tabelle Stand,
> notwendige Beispielvariablen und Abnahmebefehle. Trenne Bewertung 1 vom neuen
> Umfang. Schreibe die eigene Spezifikation des Lernenden nicht um; melde
> fachliche Widersprüche zur gemeinsamen Korrektur. Prüfe alle Dokumentlinks.
> Führe die nötigen Prüfungen aus. Kein Commit oder Push vor der Prüfung des Lernenden.

**Test:** `mvn clean test`, `docker compose config --quiet`, S8-Quelltextprüfung
und Kontrolle, dass `.env`, `tempContext/` und Prüfartefakte nicht versioniert sind.

**Geprüft und korrigiert:** 13.11.2026, Gesamtlauf um 15:39 Uhr erfolgreich:
36 Chat-Service- und 101 Writer-Tests, keine Fehler oder übersprungenen Tests.
CodeStyleTest prüft geschriebene Java-Deklarationen mit dem JDK-Parser; deutsche
Zweckkommentare und Lesbarkeit zusätzlich gelesen. 19 fehlende Kommentare in
Vorlagendateien ergänzt und überholte Gateway-/Atomaritätsaussagen korrigiert.
Die vier alten Spring-Testkontexte werden nach ihrer Klasse geschlossen.
Korrigiert: Der Überlauftest benutzte dieselbe ID zweimal; die neue Duplikatsperre
ignoriert sie zu Recht. Jetzt werden Duplikatannahme und Überlauf mit neuer ID
getrennt geprüft. Ein weiterer Gesamtlauf scheiterte beim RabbitMQ-Start während
gleichzeitigem Image-Build; nach Ende des Builds sequenziell vollständig grün.
Compose-Konfiguration gültig, lokale Dokumentlinks vorhanden, keine versionierte
.env oder tempContext-Dateien und keine kyrillischen Texte in Abgabedateien.
README auf Bewertung 2 aktualisiert; eigene Spezifikation nicht umgeschrieben.
Der Lernende hat den persönlichen Review und die Annahme von Schritt 6 bestätigt.
Seine zusätzliche Vorgabe bleibt: Abgabedateien nur deutsch/englisch.

### Schritt 7 — Compose-Abnahme und Ausfälle

**Ziel und Reihenfolge:** Nach Implementierung und Stilprüfung alle Szenarien
zusammen im Compose-Betrieb prüfen. Queue-Lebensdauer, Prozesspausen und echte
Ausfälle werden damit vor der Schlussabnahme sichtbar.

**Auftrag an die KI im Wortlaut:**

> Ergänze die reproduzierbaren Compose-Prüfwerkzeuge aus unserem Plan unter
> chat-service/src/test/acceptance. Das Host-Skript verify-live.ps1 unterstützt
> -Scenario S2 bis S8 sowie All für den zusammenhängenden Lauf S2 bis S8.
> Ein Host-Skript darf Docker steuern; SSE-
> Testclients laufen im internen chat-net und sprechen jede Instanz direkt an.
> Prüfe S2 bis S8 in Reihenfolge auf demselben Stack. Verwende keine neuen
> veröffentlichten Ports und kein Docker-Socket im Testclient. Messe während
> der Pause ready und unacknowledged, nicht nur nach dem Fortsetzen. Prüfe bei
> S5 die vollständigen 100 IDs nach Wiederanlauf. Prüfe S4 auch mit offenen SSE-
> Verbindungen. Ermittle vor jedem Szenario erneut die gültigen Container-Adressen,
> besonders nach Scale-down und erneutem Scale-up. Behebe Fehler im Rahmen des
> Plans. Beachte CLAUDE.md. Berichte Messwerte und Änderungen. Kein Commit oder
> Push vor der Prüfung des Lernenden.

**Geplanter Prüfaufruf:**
`powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario All`.
Die einzelnen Messaufrufe sind in Abschnitt 6.1 der Spezifikation dokumentiert.

Prüfwerkzeuge: PowerShell als Docker-Steuerung auf dem vorhandenen
Windows-Rechner; das kleine Skript `live-client.mjs` mit eingebauten HTTP-/JSON-Funktionen in
einem temporären `node:22-alpine`-Prüfcontainer. Dies ist Testwerkzeug, kein neuer
Produktionsdienst oder zusätzliches Produkt-Frontend. Java/JUnit und Testcontainers
bleiben die automatischen Anwendungstests im normalen Maven-Gesamtlauf.

Der Prüflauf darf keine Volumes löschen. Ergebnisse nach
`chat-service/target/acceptance/` schreiben; dieser Pfad ist als Build-Artefakt
ignoriert. Frischer Klon/Build und abschliessendes S8 werden in Schritt 8 ergänzt.

SSE-Testclients müssen UTF-8, mehrere `data:`-Zeilen, aufgeteilte Netzwerkpakete und
Kommentare korrekt behandeln. Nur vollständige Chat-Ereignisse zählen. Vor dem
Senden die tatsächliche Bereitschaft jedes Streams abwarten und nach dem erwarteten
Empfang auch auf zusätzliche IDs/Duplikate prüfen. B bleibt während der gesamten
S3-Sendeserie verbunden; keine Vorab-Schliessung, die einen Raumfilterfehler verdeckt.
Neue Räume/IDs trennen Messreihen, nicht das Löschen gespeicherter Daten.

Das Host-Skript setzt bei Fehlern einen Exit-Code ungleich null. Es setzt pausierte
Container in einer abschliessenden Aufräumphase fort, startet gestoppte Dienste
wieder und beendet eigene Testclients. Messungen werden nicht durch diese
Fehlerbehandlung als erfolgreich ausgegeben. Queue-Zustände werden nur gelesen,
nie mit `basic.get`, Purge oder Löschen für die Messung verändert.

**Geprüft und korrigiert:** 13.11.2026, zusammenhängender Lauf S2–S8 unter Windows
PowerShell 5.1 erfolgreich. Vier Dienste ohne veröffentlichte Host-Ports;
S3 zweimal exakt 400 vollständige Nachrichten, keine Duplikate, Raum B leer.
S4 bei offenen SSE-Verbindungen: zwei Queues mit je einem Consumer auf eine
in 7.678 s. S5 alle 100 live in 619 ms ab erstem POST bzw. 11 ms ab letztem POST;
nach Wiederanlauf genau dieselben 100 IDs in PostgreSQL.
S6 aktive Instanz empfängt alle 5000; grösster gemessener Rückstau 1000 inklusive
unbestätigter Lieferung; nach Fortsetzen neue ID an zuvor pausierter Instanz.
S7 genau 50 nach 10 s, ohne Duplikate, anschliessend Live; S8 erfolgreich.
Korrigiert wurden ausschliesslich Prüfwerkzeugfehler: JSON-Array-Ausgabe von
PowerShell 5.1 einzeln weiterreichen, AMQP-Argumente als typisierte Dreierlisten
auswerten, UTF-8-BOM im ersten Steuersignal tolerieren und den Originalfehler
nicht durch bereits laufendes --rm verdecken. Vor SQL-Abfrage wird der gesunde
PostgreSQL-Wiederanlauf explizit abgewartet; alte eigene Prüfartefakte werden vor
einem Lauf entfernt. Diese letzten Robustheitsänderungen werden im frischen Klon
von Schritt 8 vollständig mitgeprüft. Kein Produktionscode geändert.
Der Lernende hat den persönlichen Review und die Annahme von Schritt 7
am 13.11.2026 im Gespräch bestätigt.

### Schritt 8 — Frischer Klon und vollständige Schlussabnahme

**Ziel und Reihenfolge:** Die Abgabe wird aus dem tatsächlich versionierten Stand
und der einzigen Konfigurationsvorlage geprüft, ohne Hilfe aus lokalen Restdateien.

**Auftrag an die KI im Wortlaut:**

> Führe die Schlussabnahme aus einem neuen lokalen Klon des geprüften Commits
> durch. Erstelle nur .env aus .env.example. Prüfe S1 bis S8 in der vorgegebenen
> Reihenfolge auf demselben Stack. Halte UUID-Mengen, Reihenfolge, Zeiten,
> Queue-Zahlen und den Commit in einem Abnahmebericht fest. Melde fehlgeschlagene
> Szenarien ehrlich und korrigiere sie nur mit nachvollziehbarem Planverlauf.
> Behalte existierende Datenvolumes der normalen Arbeitsumgebung bei. Setze oder
> pushe keinen Tag und veröffentliche nichts ohne Zustimmung des Lernenden.

**Test:** Frischer Klon, `mvn clean test`, `.env.example` als Vorlage,
`docker compose up -d --build`, vollständiger Compose-Prüflauf und S8.
Der Abschlussbericht nennt die wirklich geprüften Dateien und Messwerte.

Vor dem Start des frischen Klons wird der normale Arbeitsstack kontrolliert mit
`docker compose down` ohne `-v` beendet. Beide Projekte verwenden den festen
Netznamen `chat-net`; parallele Stacks würden die Instanzzuordnung und DNS-Prüfung
verfälschen. Der Prüfklon bekommt einen eigenen Compose-Projektnamen und damit
eigene Datenvolumes. Innerhalb seiner Abnahme werden diese nicht zurückgesetzt.

**Geprüft und korrigiert:** 13.11.2026, frischer lokaler Git-Klon von
`2a7cff5498dbb148d58056833c44cc779a339418`; sauberer versionierter Stand,
.env ausschliesslich und bytegleich aus .env.example. Persönlichen Stack vorher
ohne Volumenlöschung beendet; Prüfprojekt m321-b2-abnahme mit eigenen neuen Volumes.
Zuerst S1: `mvn clean test`, 137 Tests, keine Fehler oder übersprungenen Tests,
BUILD SUCCESS um 16:02:35. Danach S2–S8 zusammenhängend auf demselben Stack grün,
Exit-Code 0. Vier Dienste ohne Host-Ports; S3 400/400/0; S4 Queue-Entfernung
in 8.772 s; S5 922 ms ab erstem POST, danach genau dieselben 100 IDs gespeichert;
S6 alle 5000, maximal 1000 inklusive unbestätigter Lieferung und neue Zustellung
nach Fortsetzen; S7 exakt 50 nach 10 s und anschliessend Live; S8 erfolgreich.
Die zusätzlichen Bereitschafts-/Artefaktkorrekturen aus Schritt 7 sind damit real
geprüft. Keine weitere Anwendungskorrektur nötig. Messwerte, Räume und Grenzen
stehen im Abnahmebericht; abschliessend nur Dokumentation ergänzt.
Der Lernende hat den persönlichen Review und die Annahme von Schritt 8 und
Schlussbericht am 13.11.2026 im Gespräch bestätigt. Anschliessend den Prüfstack
ohne Volumenlöschung beendet und den persönlichen Arbeitsstack wieder gestartet.

## 5. Veröffentlichung und Abgabe nach erfolgreicher Prüfung

Der geprüfte Stand liegt auf `main` im bisherigen Fork. Mit Zustimmung des
Lernenden wird `bewertung-2` auf diesen Stand gesetzt und gepusht. Remote-Branch
und Remote-Tag werden verglichen. `bewertung-1` bleibt unverändert.
Den Fork-Link reicht der Lernende im Abgabeportal ein.

Termin laut Lernendem: 13.11.2026 gegen Tagesende, Europe/Zurich; eine genaue
Uhrzeit wurde nicht mitgeteilt. Möglichst bis 16:00 Uhr fertig werden und Reserve
für Fehler behalten. Verspätung: 0.5 Noten Abzug.

## 6. Änderungen nach Veröffentlichung

Noch keine: Dieser Plan ist ein unveröffentlichter Entwurf. Nach der Freigabe
werden Änderungen hier mit Datum, betroffenem Schritt, Änderungsgrund, Folge für
Tests und zugehörigem Commit angehängt. Der ursprüngliche Text bleibt erhalten.

## 7. Zuordnung zu allen Bewertungsteilen

**A — Spezifikation, 8 Punkte.** Vor der Veröffentlichung die eigene Spezifikation
gegen vier Kriterien prüfen: vollständiger und belegter Vertrag, Normalfall und
alle S1–S8-Fälle mit Begründung, Queue-Name/Lebensdauer/Überlauf mit begründeten
Grenzen und messbare Abnahme mit Befehlen. Quellen und Format mit dem vorhandenen
Code und PDF abgleichen. Die KI darf gegenlesen; die eigene Darstellung des
Lernenden wird nicht durch eine heimlich neu geschriebene Spezifikation ersetzt.

**B — Plan und Arbeitsweise, 6 Punkte.** Der Plan wird vor dem ersten Code-Patch
gepusht und auf GitHub geprüft. Die neun Umsetzungsschritte 1, 2, 3a, 3b, 4, 5,
6, 7 und 8 haben jeweils Ziel, begründete Position, wörtlichen KI-Auftrag, Test
und Prüfnotiz. Pro Schritt ein fachlicher Commit; deutsche Texte und KI-Trailer.
Spätere Umplanung nur mit eigenem Änderungsgrund und Commit. Vor dem nächsten
Schritt prüft der Lernende den vorherigen und trägt tatsächliche Korrekturen ein.

**C — Szenarien, 24 Punkte.** S1 mit dem Maven-Gesamtlauf, S2 und S8 beim frischen
Start/Stilcheck, S3 mit Schritt 3b und dem Compose-Lauf, S4–S6 mit Schritt 7,
S7 mit Schritt 5 und dem Compose-Lauf. Schritt 8 prüft abschliessend S1–S8 in
Reihenfolge, aus dem neuen Klon mit `.env.example` als einziger Vorlage. Kein
Szenario gilt allein aufgrund eines Unit-Tests oder einer passenden Log-Zeile
als bestanden. Der Bericht nennt Soll-/Ist-Werte und den geprüften Commit.

**D — Tests, 6 Punkte.** Alle Anwendungstests laufen mit `mvn clean test` in einem
Lauf; erforderliche Docker-Tests werden nicht deaktiviert oder übersprungen.
`LiveDistributionIntegrationTest` enthält den echten Zwei-Instanzen-Fall S3,
`StreamReconnectIntegrationTest` den Neuverbindungsfall S7. RabbitMQ ist echt;
für den Wiederaufnahmepfad auch PostgreSQL. Der Betrieb mit Docker Compose wird
zusätzlich geprüft und nicht als Ersatz für die Maven-Tests benutzt.

**E — Code-Review, 16 Punkte.** Nach Feststehen des Abgabecodes und vor dem
Gesprächstermin erfolgt eine eigene Vorbereitung, ohne neue Produktfunktionen.
Der Lernende geht selbst durch drei Abläufe: POST an A bis SSE an B, Rückstau/Pause
aus S6 und Nachlieferung/Übergang aus S7. Zu jeder aufgerufenen Methode muss er
Datei und Zeile finden, Ursache und Wirkung erklären und den verhinderten Fehler
nennen. Insbesondere Kanalreihenfolge, Queue-Grenze gegenüber lokalem Client-Puffer,
ACK gegenüber Client-Empfang und die Rolle der DB bei Wiederaufnahme unterscheiden.
Tatsächliche verworfene oder geänderte KI-Vorschläge anhand der Prüfnotizen zeigen.
Zum Abschluss ein 20-Minuten-Probegespräch mit einer Ablauf-Frage und Rückfragen;
erkannte Wissenslücken werden gezielt wiederholt. Lernnotizen bleiben bei Bedarf
privat unter `tempContext/`. Diese Vorbereitung ersetzt nicht die Leistung im
tatsächlichen Gespräch und wird nicht vorab als bestanden markiert.

Vor dem Abgabe-Tag müssen alle vier geforderten Abgaben vorhanden sein. Der Plan
und die eigenen Tests zielen auf 60 Punkte; ihre blosse Existenz garantiert weder
die acht Ergebnisse des unbekannten Lehrerskripts noch die 16 Gesprächspunkte.

## 8. Ergänzung nach unabhängiger Nachprüfung, 13.11.2026

**Änderungsgrund:** Die unabhängige Nachprüfung des Tags `4cdcdb1` bestätigt S1–S8,
findet aber eine verspätete Cursor-Kopie nach dem Replay, eine Abweichung zur
Phasenreihenfolge der eigenen Spezifikation und verbliebene verschachtelte Aufrufe.
Die bisherige Abnahme beweist diese zusätzlichen Fälle nicht. Der Lernende hat die
Korrektur im Gespräch beauftragt. Der ursprüngliche Plan und die eigene
Spezifikation bleiben erhalten; dieser Eintrag präzisiert die folgenden Arbeiten
vor ihrer Umsetzung. Der veröffentlichte Tag wird dabei nicht still verschoben.

### Nachprüfung N1 — Wiederaufnahme korrigieren

**Ziel und Position:** Zuerst die bestätigten Fehler beheben, ohne den Vertrag der
Spezifikation umzuschreiben. Das ersetzt für die Nachprüfung die gemeinsame
Sortierung aus Abschnitt 3.2: fehlende Historie zuerst, danach während des Ladens
eingetroffene Ereignisse in Empfangsreihenfolge, anschliessend Live.

**Auftrag an die KI im Wortlaut:**

> Ergänze reale Regressionstests für verspätete Kopien des Cursors und bekannter
> Vorgänger sowie für Historie vor neu gepufferten Ereignissen mit älterer Uhrzeit.
> Behalte den Cursor und begrenzte bekannte Vorgänger-IDs über den Live-Übergang.
> Lies dafür höchstens LIVE_HISTORY_SIZE Vorgänger-IDs des Raums aus PostgreSQL
> und ergänze beobachtete RAM-Vorgänger; halte die Ausschlussmenge insgesamt auf
> LIVE_HISTORY_SIZE begrenzt, den Cursor immer eingeschlossen. Keine pauschale
> Zeitfilterung neuer Live-Ereignisse. Verwende für die SQL-Lesevorbereitung eine
> konsistente, nur lesende Repeatable-Read-Transaktion; fixiere deren Sicht mit
> SELECT 1 vor möglicherweise blockierten Tabellenabfragen. Kein SQL unter der
> Verteilungssperre oder im Live-Consumer. Sortiere nur die vorbereitete Historie,
> hänge neue Ereignisse FIFO an und entferne Überschneidungen anhand ihrer ID.
> Prüfe auch eine während des Ladens neu gespeicherte Nachricht mit älterer Zeit,
> DB-Ausfall sowie einen Cursor bei ausgeschöpftem Duplikatfenster. Bewahre alle
> bestehenden Nachrichten- und Queue-Grenzen. Keine neuen Produktfunktionen.

**Begründung:** Bekannte IDs erlauben das Unterscheiden alter Kopien von neuen
Nachrichten mit abweichender Uhrzeit. Ein konsistenter SQL-Lesestand verhindert,
dass während des Nachladens neu gespeicherte IDs nachträglich zu alten Vorgängern
erklärt werden. Die Ausschlussmenge ist begrenzt, keine globale Exactly-once-
Garantie für beliebig alte IDs. DB-Ausfall muss weiterhin 503 nur bei Replay
ergeben. Für diese Lesevorbereitung wird Spring TransactionTemplate verwendet,
damit auch Verbindungsfehler beim Transaktionsbeginn in HTTP 503 übersetzt werden.

**Test:** `mvn -pl chat-service test "-Dtest=StreamReconnectIntegrationTest,StreamSessionTest,MessageHistoryIntegrationTest"`.

**Geprüft und korrigiert:** 13.11.2026, KI-Nachprüfung: alle 14 Tests grün,
keine Fehler oder Auslassungen. Neuer realer HTTP-/RabbitMQ-/SQL-Test übernimmt
die kalte Instanz aus der unabhängigen Reproduktion: fehlende Nachricht einmal,
anschliessend keine späte Kopie von Vorgänger oder Cursor; eine neue ältere ID
wird weiterhin zugestellt. Der Tabellen-Sperrtest erwartet jetzt gespeicherte
Historie vor FIFO-Übergabe und speichert das neuere Ereignis mit älterer Uhrzeit
zusätzlich in PostgreSQL. Drei Sitzungstests prüfen auch den dauerhaft gemerkten
Cursor bei Client-Kapazität 1. SQL-Vorgängerabfrage auf Raum, UUID-Gleichstand
und Limit geprüft. DB-Ausfall bleibt 503 für Replay und beeinträchtigt Live nicht.
Der alte veröffentlichte Tag bleibt unverändert. Dies ist eine technische
KI-Prüfung, keine erfundene Bestätigung eines persönlichen Lernenden-Reviews.

### Nachprüfung N2 — Verschachtelte Aufrufe und Stil

**Ziel und Position:** Nach der Verhaltenskorrektur den gesamten Chat-Service
einschliesslich Tests gegen die tatsächlichen Regeln prüfen.

**Auftrag an die KI im Wortlaut:**

> Löse verschachtelte Methoden- und Konstruktorargumente sowie verkettete Aufrufe
> im gesamten Java-Code des Chat-Service in benannte Zwischenergebnisse auf.
> Erweitere CodeStyleTest um diese Syntaxprüfung; führe Lambda-Körper nicht so
> aus, als wären sie unmittelbare Argumentauswertung. Prüfe die neuen Regeln auch
> mit kleinen positiven und negativen Java-Beispielen. Kommentare bleiben deutsch,
> Code englisch. Behaupte weiterhin nicht, dass ein Syntaxcheck alle Regeln zu
> Sprache, Verständlichkeit und kurzen Methoden automatisch beweist.

**Test:** CodeStyleTest und anschliessend `mvn clean test`, keine deaktivierten Tests.

**Geprüft und korrigiert:** 13.11.2026, KI-Nachprüfung: `mvn clean test`
um 18:45:57 erfolgreich, 39 Chat-Service- und 101 Writer-Tests, insgesamt 140;
keine Fehler oder übersprungenen Tests. Der Syntaxcheck erfasst nun auch
verschachtelte Methoden-/Konstruktorargumente und Empfängerketten in sämtlichen
Produktions- und Testdateien. Vier negative Parser-Beispiele und ein positives
Beispiel mit aufgeschobener Lambda-Ausführung prüfen den Prüfer selbst.
Bei der mechanischen Testbereinigung verbleibende Ressourcen-, Guard- und
Lambda-Stellen wurden manuell aufgeteilt, ohne Aktionen aus ihren später
ausgeführten Lambda-Körpern vorzuziehen. Neutrale Hilfsnamen in den HTTP-/DB-
Testhilfen durch fachliche Namen ersetzt, identische Testfristen zusammengefasst.
Eine dabei falsch umbenannte connectTimeout-Methode wurde beim Kompilieren
erkannt und vor dem grünen Gesamtlauf korrigiert. Registrierung und SQL-Übergabe
in StreamService haben nun eigene kurze Methoden. Der Compose-Stilnachweis
verlangt einen nichtleeren erfolgreichen Testbericht statt genau eines Tests.
Sprache, verständliche Begründungen und sinnvolle Methodenlängen bleiben
zusätzlich eine manuelle Prüfung; Syntaxgrün allein beweist nicht alle Regeln.
Persönlicher Review des Lernenden wird hier nicht vorweggenommen.

### Nachprüfung N3 — Neue Abnahme und transparenter Abschluss

**Ziel und Position:** Erst den korrigierten Stand erneut prüfen, dann die Abgabe-
Entscheidung treffen. Alte Messwerte bleiben als historische Nachweise erkennbar.

**Auftrag an die KI im Wortlaut:**

> Ergänze README und Abnahmebericht mit den neuen Ergebnissen und ihren Grenzen.
> Prüfe S1 und S2–S8 auf dem korrigierten Stand. Erhalte den laufenden Arbeitsstack;
> verwende für zusätzliche Compose-Prüfungen einen isolierten Prüfstack mit
> eigenem Netz, falls ein Unterbruch nicht ausdrücklich freigegeben ist. Weise die
> Abweichung des Prüfnetznamens offen aus und prüfe chat-net der Originaldatei
> getrennt. Committe Korrekturen thematisch mit deutschen Texten und KI-Trailer.
> Bewahre bewertung-1. Vor Ersetzen des bereits veröffentlichten bewertung-2-Tags
> muss eine ausdrückliche Zustimmung zum konkreten korrigierten Stand vorliegen.

**Test:** Vollständiger Maven-Lauf, zusammenhängende Compose-Abnahme, Dateiausschlüsse,
Sprache und Dokumentlinks. Keine erfundenen persönlichen Reviews.

**Geprüft und korrigiert:** 13.11.2026, frischer Klon von
`e325883449ae16ed0ea34ea09952edde060183ee`; .env bytegleich aus .env.example.
Maven-Gesamtlauf um 18:52:09 grün: 140 Tests, keine Fehler oder Auslassungen.
Anschliessend S2–S8 auf demselben neuen isolierten Stack vollständig grün,
Exit-Code 0. Nur Netzname und externe Treiberpfade angepasst; diese Qualifikation
steht ausdrücklich im Nachprüfbericht. Node-Client und Produktcode unverändert.
S3 400/400/0; S4 Queue-Entfernung in 4.774 s; S5 513 ms und danach dieselben
100 IDs gespeichert; S6 alle 5000, Maximum ready + unacked 1000, neue Zustellung
nach Fortsetzen; S7 genau 50 nach 10 s und anschliessend Live; erweiterter S8 grün.
Originales chat-net, fehlende Host-Ports und Skalierbarkeit separat bestätigt.
Container-IDs und Startzeiten der vier persönlichen Dienste vorher/nachher gleich.
Nur eigener Prüfstack ohne Volumenlöschung beendet. Eigene Spezifikation und alle
ursprünglichen Planabschnitte unverändert; alte Abnahme historisch eingeordnet,
README und neuer Nachprüfbericht ergänzt. Deutsch/Englisch und lokale Links
geprüft. Anwendung und Tests nach dem Prüfcommit unverändert.
Veröffentlichter main/bewertung-2 bleiben bis zur ausdrücklichen Entscheidung
zum Tagwechsel beim bisherigen Stand. Kein persönlicher Review vorweggenommen.

## 9. Erneute Nachprüfung N4 — Beobachtete Nachfolger erhalten, 13.11.2026

**Änderungsgrund:** Die unabhängige Prüfung von `e4b5265` reproduziert einen
Nachrichtenverlust: eine bereits im RAM nach dem Cursor empfangene und gespeicherte
Nachricht mit älterem Zeitstempel wird durch die SQL-Vorgängermenge ausgeschlossen.
Die alte Version konnte diesen Fall. Das widerspricht der vorgesehenen Priorität
der beobachteten RAM-Position. Der Lernende beauftragt die Korrektur im Gespräch.
Die bisherigen Planabschnitte und Spezifikation bleiben unverändert.

**Ziel und Position:** Die Ausschlussmenge vor dem Replay richtig abgleichen,
ohne die zuvor behobenen Cursor-, Vorgänger- oder Übergabefehler wieder zu öffnen.

**Auftrag an die KI im Wortlaut:**

> Ergänze zuerst einen echten HTTP-/RabbitMQ-/PostgreSQL-Regressionstest: Cursor
> per POST annehmen, danach ein Ereignis mit älterem Zeitpunkt über den Broker
> empfangen, beide speichern und ihre RAM-Reihenfolge nachweisen. Wiederaufnahme
> muss das Ereignis vor einer neuen Live-Markierung genau einmal ausgeben.
> Prüfe auch gleichen Zeitpunkt mit kleinerer UUID und einen echten RAM-Vorgänger
> mit neuerem Zeitpunkt. Zeige den Fehler vor der Änderung. Ermittle anschliessend
> begrenzte IDs der im RAM tatsächlich nach dem Cursor empfangenen Nachrichten
> desselben Raums. Solche IDs dürfen nicht zusätzlich als SQL-Vorgänger gesperrt
> werden. Cursor und echte bekannte Vorgänger bleiben ausgeschlossen. Bei fehlendem
> Cursor im RAM bleibt das bisherige SQL-Verhalten erhalten. Keine neuen Abfragen,
> Konfigurationswerte, Frameworks oder unbeschränkten Mengen. Führe die bestehenden
> Replay-/Sitzungs-/Stiltests und die vollständige Abnahme im frischen Klon aus.

**Tests:** Neuer parametrisierter Regressionstest zunächst rot; danach
`mvn -pl chat-service test "-Dtest=StreamReconnectIntegrationTest,StreamSessionTest,CodeStyleTest"`.
Im frischen Klon anschliessend `mvn clean test` und S2–S8 in Reihenfolge auf
demselben isolierten Stack. Persönlichen Stack nicht unterbrechen. Netzabweichung
offen dokumentieren und Originalkonfiguration separat prüfen. Neue Ergebnisse
mit Datum ergänzen; bereits veröffentlichte Tags nicht ohne neue Freigabe ersetzen.

**Geprüft und korrigiert:** 13.11.2026: Neuer Regressionstest zuerst gegen den
unveränderten Fehlerstand ausgeführt, beide Varianten rot: statt des fehlenden
Ereignisses kam nur die neue Live-Markierung. Anschliessend eine begrenzte Menge
beobachteter RAM-Nachfolger ergänzt und diese IDs beim Hinzufügen von SQL-
Vorgängern übersprungen. Keine neue Abfrage, kein neues Framework und keine
Änderung der Ausschlussgrenze. Danach 17 Replay-/Sitzungs-/Stiltests grün, keine
Fehler oder Auslassungen. Der Test prüft ältere Zeit und gleichen Zeitpunkt mit
kleinerer UUID, einen tatsächlichen Vorgänger mit neuerer Uhr sowie anschliessende
Live-Zustellung und Ausbleiben alter Kopien. Die bisherigen kalten Cursor- und
Vorgängerfälle sowie SQL-/Live-Übergabe bleiben grün. Vollständige frische Abnahme
und deren Ergebnisse folgen als eigener Nachtrag nach diesem Korrekturcommit.

Abschluss N4: Frischer Klon von `3a5c46322adf94833db70bd6f35fbb65cd08eaab`,
.env bytegleich aus .env.example; `mvn clean test` um 22:14:03 erfolgreich,
142 Tests (41 Chat-Service, 101 Writer), keine Fehler oder Auslassungen.
Danach S2–S8 auf einem neuen isolierten Stack zusammenhängend grün, Exit-Code 0:
400/400/0; Queue-Entfernung 6.340 s; 100 live in 404 ms und dieselben IDs danach
gespeichert; 5000 an aktivem Client, Queue-Maximum 1000 und neue Zustellung nach
Fortsetzen; genau 50 nach 10 s und anschliessend Live; Stilcheck grün.
Netzabweichung und externe Treiberanpassung im Bericht ausdrücklich dokumentiert.
Originales chat-net, Portregeln und Skalierbarkeit separat geprüft. Dieselben vier
persönlichen Container und Startzeiten vorher/nachher; nur eigenen Prüfstack
ohne -v beendet. README und Nachprüfbericht nachgeführt, alte Ergebnisse und
eigene Spezifikation erhalten. Nach Abnahme keine Änderung an Anwendung oder Tests.
Veröffentlichter Bewertung-2-Tag bleibt bis zu neuer ausdrücklicher Freigabe auf
e4b5265. Dies sind technische KI-Prüfnotizen, kein erfundener persönlicher Review.
