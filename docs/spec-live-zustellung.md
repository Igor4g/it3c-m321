# Spezifikation — Live-Zustellung

> Arbeitsstand: Die Einleitung wurde vom Lernenden verfasst und unverändert
> übernommen. Die Abschnitte zu Vertrag, Queues, Live-Zustellung und dem
> Normalfall der Wiederaufnahme wurden vom Lernenden entworfen und mit
> KI-Unterstützung geprüft. Ein eigener Entwurf zu Fehlerfällen und Grenzen liegt
> ebenfalls vor. Die Szenarien S1–S8 wurden vom Lernenden beschrieben und gegen
> den Auftrag geprüft. Messbefehle sind vorab geplant; die technischen
> Präzisierungen stehen im Umsetzungsplan zur gemeinsamen Prüfung.
> Vom Lernenden am 13.11.2026 als Grundlage freigegeben. Dieser Stand liegt vor
> der Implementierung; Testergebnisse werden später getrennt nachgewiesen.

## 1. Ziel und Umfang

In der ersten Bewertung haben wir erreicht, dass Nachrichten die gültig sind dauerhaft in PostgreSQL gespeichert werden. Ungültige Nachrichten werden an separate RabbitMQ-Queue "chat.dlq" weitergeleitet. Dort können sie später untersucht werden um alle mögliche Fehler zu finden und zu beheben.
Uns fehlt aber noch die Zustellung der Nachrichten an die Clients (Benutzern). Bisher werden die Nachrichten gespeichert aber noch nicht an die verbundenen Clients weitergeleitet. Das Ziel dieser zweiter Bewertung ist: neue Nachrichten sofort an alle verbundenen Benutzer im gleichen Room zuzustellen.
Zu dieser Bewertung gehört nicht ein Lesestatus und "gelifiert" Status, Benutzeroberfläche, Keycloak und gateway.

## 2. Nachrichtenvertrag und SSE-Schnittstelle

Unser "chat-service" nimmt alle neue Nachrichten übers "POST /messages" entgegen. Anschliessend veröffentlicht er fertige Nachrichten über RabbitMQ im "chat.delivery". Für diese Veröffentlichung verwendet er kein aber HTTP "POST". Nachricht enthält dieselben sechs Felder wie im Schreibpfad "chat.persist": id, roomId zu dem es gehört, senderId (wer das gesendet hat), senderName, content (Inhalt) und sentAt as Datum (Zeitpunkt vom Server gesetzt)
Alle Clients können sich über "GET /stream?roomId=..." mit Stream des gewünschte Raums verbinden. Server antwortet mit dem Content-Type "text/event-stream".
Jedes Nachrichtenereignis enthält die Nachrichten-ID im so genannte "SSE Feld" - "id:" und die vollständige Nachricht als JSON "data:". "data:" bedeutet hier Daten;
Nach irgendeinem Verbindungsabbruch übermittelt unser Client bei Neuverbindung die ID der zuletzt empfangene Nachricht im Header als "Last-Event-ID". Er sendet dort aber nicht die ganze Nachricht.

### Belege zum Vertrag

- Auftrag Bewertung 2, Seite 3: Endpunkt, Content-Type, SSE-Felder, JSON-Format und
  Szenario S7 mit `Last-Event-ID`.
- [`QueueNames.java`](../chat-service/src/main/java/ch/benedict/m321/chatservice/config/QueueNames.java): Name des Exchange und der Persistenz-Queue.
- [`MessagePublisher.java`](../chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessagePublisher.java): Veröffentlichung über RabbitMQ in beide Wege.
- [`MessageController.java`](../chat-service/src/main/java/ch/benedict/m321/chatservice/controller/MessageController.java): vorhandener POST-Endpunkt.
- [`ChatMessage.java`](../chat-service/src/main/java/ch/benedict/m321/chatservice/dto/ChatMessage.java): sechs Felder und ihre Java-Typen.
- [`MessageService.java`](../chat-service/src/main/java/ch/benedict/m321/chatservice/service/MessageService.java): serverseitige Vergabe von UUID und Zeitpunkt.

## 3. Warteschlangen

Jede Instanz verfügt über eigene Warteschlange, um ihre Kopie der Nachricht zu erhalten.
Wenn eine Instanz den Betrieb einstellt und ihre Verbindung geschlossen wird, wird die temporäre Warteschlange gelöscht.
Die Anzahl der wartenden und der bereits ausgelieferten aber noch nicht bestätigten Nachrichten wird insgesamt auf 1000 begrenzt.
Bei Überlauf werden die ältesten Kopien der Live-Zustellung verworfen. Die Warteschlange "chat.persist“ ist von dieser Begrenzung nicht betroffen.

### 3.1 Namen, Anbindung und Begrenzung

Jede Instanz unseres Chat-Dienstes verfügt über eine eigene temporäre "Warteschlange" (Queue) mit eindeutigen Namen, der wird automatisch von Spring generiert wird. Jede Warteschlange wird mit dem Exchange "chat.delivery“ verbunden um eine eigene Kopie jeder Nachricht zu erhalten. Nachrichten aus jeder Warteschlange werden von einem einzigen Verarbeiter empfangen.: Consumer.
In Warteschlange können maximal 999 Nachrichten warten. Eine weitere Nachricht kann bereits an Consumer übergeben worden sein, wurde aber noch nicht bestätigt. Daher wird die Gesamtzahl 1000 nicht überschreiten. Die Einstellung "prefetch=1“ bedeutet, dass RabbitMQ dem Consumer die nächste Nachricht erst nach Bestätigung der vorherigen ausgibt

## 4. Live-Zustellung an die Clients

Jede Instanz des Chat-Dienstes speichert eine Liste der mit ihr verbundenen Clients und weiss, welchen Raum jeder Client ausgewählt hat. Wenn eine Instanz aber eine Nachricht aus ihrer Warteschlange erhält, überprüft sie deren "roomId". Nachricht wird über SSE nur an diejenigen verbundenen Clients gesendet, die diesen Raum ausgewählt haben. Clients in anderen Räumen erhalten sie nicht.
Für die "Live-Übertragung" muss nicht auf die Speicherung in PostgreSQL gewartet werden. Daher erhalten die Clients weiterhin neue Nachrichten, selbst wenn die DB und batch-writer vorübergehend deaktiviert sind.
Wenn ein Client die Verbindung trennt, wird seine Verbindung aus der Liste einfach entfernt. Warteschlange der Instanz bleibt dabei trotzdem bestehen: Sie gehört zur Instanz, die andere Clients bedienen kann.
Ein langsamer Client darf anderen in dem Fall nicht aufhalten. Daher schlagen wir für jede Client-Verbindung einen separaten, begrenzten Nachrichtenpuffer vor. Wenn dieser überläuft, schliesst der Server genau diese Verbindung. Der Client kann sich erneut verbinden und die verpassten Nachrichten anfordern.

## 5. Wiederaufnahme nach einem Verbindungsabbruch

Bei erster Verbindung ohne Header "Last-Event-ID“ erhält unser Client die neue Nachrichte des von ihm ausgewählten Raums ab dem Zeitpunkt, an dem die Verbindung im Dienst registriert wird. Es ist aber nicht erforderlich, den alten Verlauf automatisch zu senden.
Nach einer Unterbrechung der Verbindung stellt Client die Verbindung erneut her und übermittelt die ID der zuletzt empfangenen Nachricht. Der Server verwendet diese ID, um festzustellen, welche Nachrichten des ausgewählten Raums der Client verpasst hat. Zuletzt empfangene Nachricht selbst muss nicht erneut gesendet werden.
Gespeicherten Nachrichten liest der Server aus PostgreSQL aus. Es kann jedoch vorkommen, dass der "batch-writer" die allerneuesten Nachrichten noch nicht gespeichert hat. Daher speichert "chat-service" zusätzlich eine begrenzte Anzahl der jüngsten Nachrichten im Arbeitsspeicher, auch wenn zu diesem Zeitpunkt keine Clients mit dem Raum verbunden sind. Server führt gespeicherte und aktuelle Nachrichten zusammen. Wird eine Nachricht mit derselben ID sowohl in der Datenbank als auch im Arbeitsspeicher gefunden, wird sie dem Client nur einmal gesendet. Während der Server die versäumten Nachrichten aufbereitet, können neue Nachrichten eintreffen. Für diese Verbindung werden sie vorübergehend gesammelt. Client erhält zunächst die versäumten Nachrichten in der Reihenfolge des jeweiligen Absenders, wie in S3 gefordert, dann die angesammelten neuen Nachrichten und empfängt anschliessend die Nachrichten wieder im üblichen Live-Modus.

### 5.1 Fehlerfälle und Grenzen

Wenn roomId fehlt oder ein falsches UUID-Format aufweist, lehnt der Server Anfrage mit HTTP-Statuscode 400 ab. Ein falsches Format der "Last-Event-ID" führt ebenfalls zu einem HTTP 400. Wenn ID das richtige Format hat, die entsprechende Nachricht jedoch weder im Arbeitsspeicher noch in Datenbank gefunden wird oder zu einem anderen Raum gehört, dann empfehlen wir HTTPStatuscode 404 zurückzugeben
Für die Wiederherstellung der fehlenden Nachrichten muss PostgreSQL erreichbar sein. Sollte PostgreSQL vorübergehend nicht erreichbar sein, gibt der Server HTTP 503 zurück. Dabei funktioniert die normale Live-Übertragung ohne Abfrage des Verlaufs weiterhin.
Fürs Verlauf im Arbeitsspeicher könnten wir so machen: die letzte 10.000 Nachrichten pro Instanz zu speichern. Für Puffer einer einzelnen Client-Verbindung können wirebenfalls eine Obergrenze von 10.000 Nachrichten haben. Dabei handelt es sich um separate Puffer der Anwendung: Begrenzung der RabbitMQ-Warteschlange beträgt weiterhin 999 wartende Nachrichten plus eine unbestätigte Nachricht.
Pro Wiederverbindungsvorgang wäre möglich, maximal 10.000 Nachrichten wiederherzustellen. Sind es mehr, meldet der Server vor Beginn des Streams HTTP 409, anstatt nur einen Teil zu senden und diesen als vollständigen Verlauf darzustellen. Diese Werte bieten eine Reserve gegenüber der grössten Testreihe des Dozenten: 5.000 Nachrichten.
Wird ein Fehler vor Beginn der SSE Antwort festgestellt, kann unser Server einen HTTP Fehlercode zurückgeben. Hat der Datenstrom bereits begonnen dann kann er nicht durch eine neue HTTP Antwort ersetzt werden: bei Pufferüberlauf oder Übertragungsfehler wird die betroffene Verbindung geschlossen.

## 6. Abnahmekriterien S1–S8

### S1.

Wir führen "mvn clean test“ im Stammverzeichnis des Projekts aus. Alle Tests müssen in einem Durchlauf erfolgreich abgeschlossen werden.

### S2.

Wir klonen das Repository in einen neuen Ordner, erstellen die Datei ".env“ anhand von ".env.example“ und führen "docker compose up -d --build“ aus. Alle Dienste müssen ohne nach aussen veröffentlichte Ports funktionieren.

### S3.

Wir starten zwei Instanzen des "chat-service“. Zu jeder verbinden wir einen Client des Raums A. Zusätzlich verbinden wir einen dritten Client mit Raum B. Über jede Instanz senden wir jeweils 200 Nachrichten an A. Beide Clients von A müssen alle 400 Nachrichten ohne Duplikate erhalten, wobei die Reihenfolge der Nachrichten jedes Absenders beibehalten wird. Der Client von B darf diese Nachrichten nicht erhalten.

### S4.

Wir reduzieren die Anzahl der Instanzen von zwei auf eine. Vor der Reduzierung müssen genau zwei mit dem Fanout-Exchange verbundene Live-Zustellungswarteschlangen mit jeweils einem Consumer vorhanden sein. Spätestens nach 30 Sekunden darf nur noch eine übrig sein. Die Warteschlangen "chat.persist“ und "chat.dlq“ müssen erhalten bleiben.

### S5.

Wir stoppen PostgreSQL und den "batch-writer" und senden anschließend 100 Nachrichten. Alle Nachrichten müssen innerhalb von maximal 5 Sekunden beim Client eintreffen. Nach dem Neustart der angehaltenen Dienste müssen alle 100 Nachrichten in der Datenbank vorhanden sein.

### S6.

Wir pausieren eine der beiden Instanzen mit "docker pause" und senden 5.000 Nachrichten über die laufende Instanz. Die Warteschlange der pausierten Instanz darf das festgelegte Limit von 1.000 nicht überschreiten. Der Client der aktiven Instanz muss alle 5.000 Nachrichten erhalten. Nach dem Fortsetzen mit "docker unpause" muss die pausierte Instanz wieder neue Nachrichten zustellen.

### S7.

Der Client empfängt eine Nachricht und trennt die Verbindung. Wir senden weitere 50 Nachrichten. Nach 10 Sekunden stellt der Client mit dem Header "Last-Event-ID" eine Verbindung her. Er muss genau 50 verpasste Nachrichten der Reihe nach und ohne Duplikate erhalten und anschliessend den Empfang neuer Nachrichten fortsetzen.

### S8.

Wir überprüfen den gesamten "chat-service" auf Übereinstimmung mit "CLAUDE.md": keine Java-Streams, deutsche Kommentare über Klassen und Methoden sowie die übrigen Projektregeln. Die .env-Datei darf nicht in Git enthalten sein.

### 6.1 Geplante Messbefehle zu den eigenen Kriterien

Technische Ergänzung aus der Gegenprüfung: Die folgenden Prüfaufrufe werden vor der Implementierung festgelegt. Das Skript unter "src/test/acceptance" wird gemäss Umsetzungsplan erst gebaut. Diese Beschreibung behauptet keinen bereits bestandenen Testlauf. Die eigenen Kriterien in S1–S8 bleiben der Massstab. Alle Aufrufe erfolgen im Projektstamm mit Java 21, Maven 3.9 und Docker. Im frischen Klon wird ".env" mit "Copy-Item .env.example .env" angelegt. Einen bestehenden persönlichen ".env" - Inhalt dadurch nicht überschreiben.
Zuerst führen wir für S1 "mvn clean test" aus. Der gesamte Testlauf muss grün sein. Erforderliche Integrationstests dürfen nicht übersprungen werden.

Für S2 starten wir im frischen Klon "docker compose up -d --build". Danach führen wir "powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario S2" aus. Wir prüfen, ob alle Dienste laufen und bei keinem Dienst ein Host-Port veröffentlicht wird.

Für S3 führen wir "powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario S3" aus. Bei jedem Client prüfen wir die genaue Menge der Nachrichten-IDs, die Häufigkeit jeder ID, den JSON-Inhalt und die Reihenfolge je Absender. Beide Clients in Raum A müssen alle 400 Nachrichten jeweils einmal erhalten. Der Client in Raum B darf keine dieser Nachrichten erhalten.

Für S4 führen wir "powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario S4" aus. Vor und nach dem Verkleinern zählen wir die an den Fanout-Exchange gebundenen Queues und ihre Consumer. Wir messen, ob innerhalb von 30 Sekunden genau eine Live-Queue übrig bleibt. "chat.persist" und "chat.dlq" müssen erhalten bleiben.

Für S5 führen wir "powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario S5" aus. Wir messen den Empfang aller 100 Nachrichten innerhalb von 5 Sekunden. Nach dem Wiederanlauf von PostgreSQL und Writer prüfen wir mit SQL, ob genau dieselben 100 Nachrichten-IDs gespeichert wurden.

Für S6 führen wir "powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario S6" aus. Wir prüfen die Queue-Einstellungen und erfassen während der Pause fortlaufend die wartenden und unbestätigten Nachrichten. Die Summe darf 1000 nicht überschreiten. Beim aktiven Client müssen alle 5000 IDs ankommen.
Nach dem Fortsetzen prüfen wir die Zustellung einer neuen Nachricht an der
zuvor pausierten Instanz anhand ihrer ID.

Für S7 führen wir "powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario S7" aus. Wir prüfen die genauen 50 erwarteten IDs, ihre Reihenfolge und das Fehlen von Duplikaten. Die letzte vor der Trennung empfangene Nachricht darf nicht erneut erscheinen. Anschliessend muss eine neue Live-Nachricht ankommen.

Für S8 führen wir zuerst "mvn -pl chat-service test "-Dtest=CodeStyleTest"" aus. Danach folgt "powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario S8". Der Test prüft Kommentare und das Verbot von Java-Streams. Zusätzlich prüfen wir die deutsche Sprache der Kommentare und die Lesbarkeit manuell. Mit Git kontrollieren wir, dass ".env" nicht versioniert ist.

Der vollständige Lauf ist: "mvn clean test", dann "powershell -NoProfile -File chat-service/src/test/acceptance/verify-live.ps1 -Scenario All". "All" führt S2–S8 in dieser Reihenfolge auf demselben Compose-Stack aus. Zwischen den Szenarien werden keine Volumes und keine Queues zurückgesetzt. Die einzelnen Szenario-Aufrufe dienen dem gezielten Nachprüfen und ersetzen den Gesamtlauf nicht.
Die Testclients laufen innerhalb von "chat-net" und verbinden sich mit den einzeln ermittelten Container-Adressen. Die Broker-Messung verwendet intern beispielsweise "docker compose exec -T rabbitmq rabbitmqctl list_queues --formatter=json name consumers messages_ready messages_unacknowledged arguments". Die Persistenzprüfung liest PostgreSQL innerhalb des Containers; keine zusätzlichen Host-Ports. Ergebnisse und Laufzeiten werden unter "chat-service/target/acceptance/" gespeichert. Bei S5 messen wir konservativ vom Beginn des Sendens bis zum letzten Empfang und zusätzlich ab dem letzten erfolgreichen POST. Zeitpunkt und Messwerte stehen im späteren Abnahmebericht.
