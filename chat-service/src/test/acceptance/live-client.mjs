import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createInterface } from 'node:readline';
import { setTimeout as delay } from 'node:timers/promises';

const [scenario, firstAddress, secondAddress] = process.argv.slice(2);
const first = `http://${firstAddress}:8080`;
const second = `http://${secondAddress}:8080`;
const commands = createInterface({ input: process.stdin })[Symbol.asyncIterator]();
const clients = [];

/** Eine JSON-Zeile erlaubt dem Host, Container genau an den Messpunkten zu steuern. */
function report(event, values = {}) {
    console.log(JSON.stringify({ event, scenario, ...values }));
}

/** Explizite Freigabe ersetzt zufällige Wartezeiten zwischen Host und Prüfclient. */
async function command() {
    const next = await commands.next();
    // Windows PowerShell kann dem ersten UTF-8-Befehl eine BOM voranstellen.
    assert.equal(next.value?.trim(), 'GO', 'Host must explicitly release this phase');
}

/** Ein echter HTTP-Stream zählt nur vollständige SSE-Chat-Ereignisse. */
class StreamClient {
    /** Jeder Client behält seine Ereignisse für Mengen-, Inhalts- und Duplikatprüfung. */
    constructor() {
        this.controller = new AbortController();
        this.messages = [];
        this.failure = null;
    }

    /** Der anfängliche Kommentar macht die serverseitige Registrierung sichtbar. */
    static async open(address, roomId, lastId) {
        const deadline = Date.now() + 60000;
        while (true) {
            const client = new StreamClient();
            const timeout = setTimeout(() => client.controller.abort(), 8000);
            try {
                const headers = lastId ? { 'Last-Event-ID': lastId } : {};
                const response = await fetch(`${address}/stream?roomId=${roomId}`, {
                    headers, signal: client.controller.signal
                });
                assert.equal(response.status, 200);
                assert.match(response.headers.get('content-type'), /text\/event-stream/);
                clearTimeout(timeout);
                client.reading = client.read(response.body);
                clients.push(client);
                return client;
            } catch (error) {
                clearTimeout(timeout);
                client.close();
                if (Date.now() >= deadline) throw error;
                await delay(250);
            }
        }
    }

    /** TextDecoder erhält UTF-8 auch dann, wenn Netzwerkpakete Zeichen aufteilen. */
    async read(body) {
        const decoder = new TextDecoder();
        let pending = '';
        let identifier = null;
        let data = [];
        try {
            for await (const bytes of body) {
                pending += decoder.decode(bytes, { stream: true });
                let end;
                while ((end = pending.indexOf('\n')) >= 0) {
                    const line = pending.slice(0, end).replace(/\r$/, '');
                    pending = pending.slice(end + 1);
                    if (line === '') {
                        if (data.length > 0) {
                            const message = JSON.parse(data.join('\n'));
                            assert.equal(identifier, message.id, 'SSE id must match JSON id');
                            this.messages.push(message);
                        }
                        identifier = null;
                        data = [];
                    } else if (line.startsWith('id:')) {
                        identifier = line.slice(3).replace(/^ /, '');
                    } else if (line.startsWith('data:')) {
                        data.push(line.slice(5).replace(/^ /, ''));
                    }
                }
            }
        } catch (error) {
            if (!this.controller.signal.aborted) this.failure = error;
        }
    }

    /** Eine Obergrenze verhindert, dass verlorene Nachrichten den Lauf endlos aufhalten. */
    async count(expected, timeout = 30000) {
        const deadline = Date.now() + timeout;
        while (this.messages.length < expected) {
            if (this.failure) throw this.failure;
            assert.ok(Date.now() < deadline, `Expected ${expected}, received ${this.messages.length}`);
            await delay(10);
        }
    }

    /** Nach der Sollmenge bleiben zusätzliche Ereignisse weiterhin sichtbar. */
    async quiet() {
        await delay(400);
        if (this.failure) throw this.failure;
    }

    /** Auch bei fehlgeschlagenen Szenarien werden eigene HTTP-Verbindungen beendet. */
    close() {
        this.controller.abort();
    }
}

/** Aus POST und 202-Antwort entsteht der vollständige erwartete Nachrichtenvertrag. */
async function send(address, roomId, senderId, sequence) {
    const input = { roomId, senderId, senderName: `Prüfung ${senderId}`, content: `Nachricht ${sequence}: Grüezi\nGrüsse` };
    const response = await fetch(`${address}/messages`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(input), signal: AbortSignal.timeout(10000)
    });
    assert.equal(response.status, 202);
    const accepted = await response.json();
    return { ...input, id: accepted.id, sentAt: accepted.sentAt };
}

/** Nacheinander angenommene Nachrichten eines Absenders definieren seine Sollreihenfolge. */
async function series(address, roomId, senderId, count) {
    const result = [];
    for (let index = 0; index < count; index++) {
        result.push(await send(address, roomId, senderId, index));
    }
    return result;
}

/** Nicht nur die Anzahl: jede ID, jedes Feld und die Absenderfolge müssen stimmen. */
function verify(client, expected) {
    assert.equal(client.messages.length, expected.length, 'Exact message count');
    const byId = new Map();
    for (const message of client.messages) {
        assert.ok(!byId.has(message.id), `Duplicate ${message.id}`);
        byId.set(message.id, message);
    }
    const senders = new Set();
    for (const message of expected) {
        assert.deepEqual(byId.get(message.id), message, 'All six JSON fields must match');
        senders.add(message.senderId);
    }
    for (const sender of senders) {
        const wanted = expected.filter(message => message.senderId === sender).map(message => message.id);
        const actual = client.messages.filter(message => message.senderId === sender).map(message => message.id);
        assert.deepEqual(actual, wanted, `Sender order ${sender}`);
    }
}

/** S3 prüft zwei unabhängige Instanzen und hält Raum B bis zum Ende offen. */
async function distribution() {
    const roomId = randomUUID();
    const firstClient = await StreamClient.open(first, roomId);
    const secondClient = await StreamClient.open(second, roomId);
    const otherRoom = await StreamClient.open(second, randomUUID());
    const batches = await Promise.all([
        series(first, roomId, 'sender-a', 200), series(second, roomId, 'sender-b', 200)
    ]);
    const expected = batches.flat();
    await Promise.all([firstClient.count(400), secondClient.count(400)]);
    await firstClient.quiet();
    verify(firstClient, expected);
    verify(secondClient, expected);
    verify(otherRoom, []);
    report('done', { roomId, firstCount: 400, secondCount: 400, otherRoomCount: 0 });
}

/** Offene SSE-Verbindungen dürfen das Entfernen einer Instanz nicht verhindern. */
async function scaleDown() {
    const roomId = randomUUID();
    await StreamClient.open(first, roomId);
    await StreamClient.open(second, roomId);
    report('ready');
    await command();
    report('done');
}

/** Live muss auch während des echten Stillstands beider Persistenzdienste laufen. */
async function databaseOutage() {
    const roomId = randomUUID();
    const client = await StreamClient.open(first, roomId);
    report('ready');
    await command();
    const started = performance.now();
    const expected = await series(first, roomId, 'db-outage', 100);
    const lastPost = performance.now();
    await client.count(100, 5000);
    const received = performance.now();
    const elapsedMs = Math.round(received - started);
    const afterLastPostMs = Math.round(received - lastPost);
    assert.ok(elapsedMs <= 5000, `Live delivery took ${elapsedMs} ms`);
    await client.quiet();
    verify(client, expected);
    report('done', { roomId, ids: expected.map(message => message.id), elapsedMs, afterLastPostMs });
}

/** S6 lässt den Host während der Pause messen, nicht erst nach dem Fortsetzen. */
async function pausedInstance() {
    const roomId = randomUUID();
    await StreamClient.open(first, roomId);
    const activeClient = await StreamClient.open(second, roomId);
    report('ready');
    await command();
    const expected = [];
    for (let batch = 0; batch < 5; batch++) {
        const messages = await series(second, roomId, 'active', 1000);
        expected.push(...messages);
        report('sample', { sent: expected.length });
        await command();
    }
    await activeClient.count(5000, 60000);
    await activeClient.quiet();
    verify(activeClient, expected);
    report('resume', { activeCount: 5000 });
    await command();
    const resumedClient = await StreamClient.open(first, roomId);
    // Alte Restkopien sind hier erlaubt; nachgewiesen wird eine eindeutig neue ID.
    await delay(500);
    const fresh = await send(second, roomId, 'after-resume', 1);
    const deadline = Date.now() + 30000;
    while (!resumedClient.messages.some(message => message.id === fresh.id)) {
        assert.ok(Date.now() < deadline, 'Resumed instance must deliver a new message');
        if (resumedClient.failure) throw resumedClient.failure;
        await delay(20);
    }
    const copies = resumedClient.messages.filter(message => message.id === fresh.id);
    assert.deepEqual(copies, [fresh]);
    report('done', { roomId, activeCount: 5000, resumedNewId: fresh.id });
}

/** S7 nutzt den echten Writer statt direkt erzeugter Datenbank-Testzeilen. */
async function reconnect() {
    const roomId = randomUUID();
    const initial = await StreamClient.open(first, roomId);
    const cursor = await send(first, roomId, 'reconnect', 0);
    await initial.count(1);
    initial.close();
    const expected = await series(first, roomId, 'reconnect', 50);
    await delay(10000);
    const resumed = await StreamClient.open(second, roomId, cursor.id);
    await resumed.count(50);
    await resumed.quiet();
    verify(resumed, expected);
    const fresh = await send(first, roomId, 'reconnect', 51);
    await resumed.count(51);
    await resumed.quiet();
    verify(resumed, [...expected, fresh]);
    report('done', { roomId, cursorId: cursor.id, replayCount: 50, subsequentLiveCount: 1 });
}

/** Ein Szenariofehler liefert einen echten Fehlercode an das steuernde Host-Skript. */
async function main() {
    try {
        if (scenario === 'S3') await distribution();
        else if (scenario === 'S4') await scaleDown();
        else if (scenario === 'S5') await databaseOutage();
        else if (scenario === 'S6') await pausedInstance();
        else if (scenario === 'S7') await reconnect();
        else throw new Error(`Unknown scenario ${scenario}`);
    } finally {
        for (const client of clients) client.close();
    }
}

const watchdog = setTimeout(() => {
    console.error('Scenario exceeded 180 seconds');
    process.exit(1);
}, 180000);
try {
    await main();
    clearTimeout(watchdog);
    process.exit(0);
} catch (error) {
    console.error(error.stack);
    process.exit(1);
}
