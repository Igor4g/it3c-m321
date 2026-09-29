package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft den Nachrichtenvertrag ohne Broker oder Datenbank.
 * Die Eingaben sind JSON-Bytes und Content-Type, keine Java-Typheader.
 */
class MessageReaderTest {

    private static final String VALID_JSON = """
            {
              "id": "11111111-1111-4111-8111-111111111111",
              "roomId": "22222222-2222-4222-8222-222222222222",
              "senderId": "anna",
              "senderName": "Anna Muster",
              "content": "Hallo zusammen",
              "sentAt": "2026-09-29T08:00:00Z"
            }
            """;

    private final MessageReader messageReader = new MessageReader();

    /** Alle sechs Werte müssen ohne Java-Klasseninformationen lesbar bleiben. */
    @Test
    void readsEveryFieldWithoutJavaTypeHeaders() {
        byte[] body = VALID_JSON.getBytes(StandardCharsets.UTF_8);

        ChatMessage message = messageReader.read(body, "application/json");

        UUID expectedId = UUID.fromString("11111111-1111-4111-8111-111111111111");
        UUID expectedRoomId = UUID.fromString("22222222-2222-4222-8222-222222222222");
        Instant expectedTime = Instant.parse("2026-09-29T08:00:00Z");
        assertEquals(expectedId, message.id());
        assertEquals(expectedRoomId, message.roomId());
        assertEquals("anna", message.senderId());
        assertEquals("Anna Muster", message.senderName());
        assertEquals("Hallo zusammen", message.content());
        assertEquals(expectedTime, message.sentAt());
    }

    /** Übliche Schreibweisen des JSON-Content-Types dürfen keine Ablehnung auslösen. */
    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/json; charset=UTF-8",
            "Application/JSON; charset=\"utf-8\""})
    void acceptsJsonContentType(String contentType) {
        byte[] body = VALID_JSON.getBytes(StandardCharsets.UTF_8);

        ChatMessage message = messageReader.read(body, contentType);

        assertEquals("Hallo zusammen", message.content());
    }

    /** Umlaute, Zeilenumbrüche und vollständige Unicode-Zeichen bleiben unverändert. */
    @Test
    void preservesUnicodeAndLineBreaks() {
        String json = VALID_JSON.replace("Hallo zusammen", "Grüezi\\nZusammen 👋");
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ChatMessage message = messageReader.read(body, "application/json");

        assertEquals("Grüezi\nZusammen 👋", message.content());
    }

    /** Ein zusätzlicher Wert soll ältere Consumer nicht an der Verarbeitung hindern. */
    @Test
    void ignoresUnknownFields() throws Exception {
        byte[] body = withField("futureField", "{\"enabled\":true}");

        ChatMessage message = messageReader.read(body, "application/json");

        assertEquals("Hallo zusammen", message.content());
    }

    /** Unterschiedliche Zeitzonenangaben können denselben Zeitpunkt beschreiben. */
    @Test
    void acceptsTimestampWithOffset() throws Exception {
        byte[] body = withField("sentAt", "\"2026-09-29T10:00:00+02:00\"");
        Instant expectedTime = Instant.parse("2026-09-29T08:00:00Z");

        ChatMessage message = messageReader.read(body, "application/json");

        assertEquals(expectedTime, message.sentAt());
    }

    /** Pflichtfelder dürfen auch bei direkter Veröffentlichung auf die Queue nicht fehlen. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "roomId", "senderId", "senderName", "content", "sentAt"})
    void rejectsMissingField(String fieldName) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(VALID_JSON);
        root.remove(fieldName);
        String json = root.toString();
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** null ist kein gültiger Ersatz für einen der sechs Vertragswerte. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "roomId", "senderId", "senderName", "content", "sentAt"})
    void rejectsNullField(String fieldName) throws Exception {
        byte[] body = withField(fieldName, "null");

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Zahlen werden nicht stillschweigend in Texte umgewandelt. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "roomId", "senderId", "senderName", "content", "sentAt"})
    void rejectsNumericField(String fieldName) throws Exception {
        byte[] body = withField(fieldName, "42");

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Auch verschachtelte Daten oder Wahrheitswerte erfüllen den Textvertrag nicht. */
    @ParameterizedTest
    @ValueSource(strings = {"true", "[]", "{}"})
    void rejectsNonTextContent(String jsonValue) throws Exception {
        byte[] body = withField("content", jsonValue);

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Leere beziehungsweise nur aus Leerraum bestehende Werte werden zurückgewiesen. */
    @ParameterizedTest
    @ValueSource(strings = {"id", "roomId", "senderId", "senderName", "content", "sentAt"})
    void rejectsBlankField(String fieldName) throws Exception {
        byte[] body = withField(fieldName, "\"   \"");

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Abgekürzte und fehlerhafte UUIDs sollen nicht als neue Identität normalisiert werden. */
    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "11111111-1111-4111-8111-11111111111g"})
    void rejectsInvalidUuid(String value) throws Exception {
        for (String fieldName : new String[]{"id", "roomId"}) {
            String jsonValue = "\"" + value + "\"";
            byte[] body = withField(fieldName, jsonValue);

            assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
        }
    }

    /** Ein Zeitwert braucht ein Datum, eine Uhrzeit und eine Zeitzonenangabe. */
    @ParameterizedTest
    @ValueSource(strings = {"not-a-time", "2026-09-29", "2026-09-29T08:00:00"})
    void rejectsInvalidTimestamp(String value) throws Exception {
        String jsonValue = "\"" + value + "\"";
        byte[] body = withField("sentAt", jsonValue);

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Pro Nachricht ist genau ein vollständiges JSON-Objekt zulässig. */
    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "{", "[]", "null", "42", "{} {}"})
    void rejectsInvalidJsonBody(String json) {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Fremde Formate und Zeichensätze dürfen nicht versehentlich als JSON gelesen werden. */
    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"text/plain", "application/xml", "application/*", "invalid",
            "application/json; charset=ISO-8859-1", "application/json; charset=unknown-charset"})
    void rejectsInvalidContentType(String contentType) {
        byte[] body = VALID_JSON.getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, contentType));
    }

    /** Eine fehlende Nutzlast wird als Datenfehler statt als Programmfehler gemeldet. */
    @Test
    void rejectsNullBody() {
        assertThrows(InvalidMessageException.class, () -> messageReader.read(null, "application/json"));
    }

    /** Ungültige UTF-8-Bytes dürfen beim Dekodieren nicht unbemerkt ersetzt werden. */
    @Test
    void rejectsMalformedUtf8() {
        byte[] body = VALID_JSON.getBytes(StandardCharsets.UTF_8);
        for (int i = 0; i < body.length; i++) {
            if (body[i] == 'H') {
                body[i] = (byte) 0xff;
                break;
            }
        }

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Nullzeichen und einzelne UTF-16-Surrogates können nicht verlustfrei gespeichert werden. */
    @ParameterizedTest
    @ValueSource(strings = {"\\u0000", "\\uD800", "\\uDC00", "\\uD800x"})
    void rejectsUnstorableText(String escapedText) {
        String json = VALID_JSON.replace("Hallo zusammen", escapedText);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Ein gültiges erstes Objekt darf einen unerlaubten zweiten JSON-Wert nicht verdecken. */
    @Test
    void rejectsTrailingJsonAfterValidMessage() {
        String json = VALID_JSON + "{}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        assertThrows(InvalidMessageException.class, () -> messageReader.read(body, "application/json"));
    }

    /** Die Prüfung darf gültige Texte nicht trimmen oder anderweitig verändern. */
    @Test
    void preservesSpacesInContent() throws Exception {
        byte[] body = withField("content", "\"  Hallo zusammen  \"");

        ChatMessage message = messageReader.read(body, "application/json");

        assertEquals("  Hallo zusammen  ", message.content());
    }

    /** Ändert gezielt ein Vertragsfeld; der Rest bleibt eine gültige Nachricht. */
    private byte[] withField(String fieldName, String jsonValue) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = (ObjectNode) mapper.readTree(VALID_JSON);
        JsonNode value = mapper.readTree(jsonValue);
        root.set(fieldName, value);
        String json = root.toString();
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
