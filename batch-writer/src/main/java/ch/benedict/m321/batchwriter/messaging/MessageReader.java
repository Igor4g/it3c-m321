package ch.benedict.m321.batchwriter.messaging;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import org.springframework.util.MimeTypeUtils;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;

/**
 * Liest den JSON-Vertrag ausdrücklich, ohne Java-Typinformationen des Absenders.
 * Ungültige Daten werden erkannt, bevor eine Datenbanktransaktion beginnen kann.
 */
@Component
public class MessageReader {

    // JDBC schreibt frühere Werte als -infinity; die obere Grenze schützt vor Rundungsüberlauf.
    private static final Instant MINIMUM_TIMESTAMP = Instant.parse("-4712-01-01T00:00:00Z");
    private static final Instant MAXIMUM_TIMESTAMP = Instant.parse("+294276-12-31T23:59:59.999999Z");

    private final ObjectMapper objectMapper;

    /** Ein zweites JSON-Objekt hinter der Nachricht darf nicht stillschweigend verschwinden. */
    public MessageReader() {
        objectMapper = new ObjectMapper();
        objectMapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    /** Übernimmt nur geprüfte Vertragswerte; zusätzliche JSON-Felder werden nicht benötigt. */
    public ChatMessage read(byte[] body, String contentType) {
        validateContentType(contentType);
        String json = decodeUtf8(body);
        JsonNode root = readObject(json);

        UUID id = readUuid(root, "id");
        UUID roomId = readUuid(root, "roomId");
        String senderId = readText(root, "senderId");
        String senderName = readText(root, "senderName");
        String content = readText(root, "content");
        Instant sentAt = readTimestamp(root);

        return new ChatMessage(id, roomId, senderId, senderName, content, sentAt);
    }

    /** Nur JSON mit UTF-8 gehört zu unserem Vertrag; andere Formate werden nicht erraten. */
    private void validateContentType(String contentType) {
        if (contentType == null) {
            throw new InvalidMessageException("Content type is required");
        }
        try {
            MimeType mimeType = MimeTypeUtils.parseMimeType(contentType);
            String type = mimeType.getType();
            String subtype = mimeType.getSubtype();
            Charset charset = mimeType.getCharset();

            if (!"application".equals(type) || !"json".equals(subtype)) {
                throw new InvalidMessageException("Content type must be application/json");
            }
            if (charset != null && !StandardCharsets.UTF_8.equals(charset)) {
                throw new InvalidMessageException("JSON must use UTF-8");
            }
        } catch (IllegalArgumentException exception) {
            throw new InvalidMessageException("Invalid content type", exception);
        }
    }

    /** Der Decoder meldet ungültige Bytes, statt sie durch Ersatzzeichen zu verändern. */
    private String decodeUtf8(byte[] body) {
        if (body == null || body.length == 0) {
            throw new InvalidMessageException("Message body is required");
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        ByteBuffer buffer = ByteBuffer.wrap(body);
        try {
            CharBuffer characters = decoder.decode(buffer);
            return characters.toString();
        } catch (CharacterCodingException exception) {
            throw new InvalidMessageException("Message body must be valid UTF-8", exception);
        }
    }

    /** Eine Nachricht besteht aus einem Objekt, nicht aus einer Liste oder einem Einzelwert. */
    private JsonNode readObject(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);
            if (root == null || !root.isObject()) {
                throw new InvalidMessageException("Message body must be a JSON object");
            }
            return root;
        } catch (JsonProcessingException exception) {
            throw new InvalidMessageException("Message body contains invalid JSON", exception);
        }
    }

    /** Verhindert fehlende Werte sowie unbemerkte Umwandlungen von Zahlen in Texte. */
    private String readText(JsonNode root, String fieldName) {
        JsonNode field = root.get(fieldName);
        if (field == null || !field.isTextual()) {
            throw new InvalidMessageException("Field '" + fieldName + "' must be a string");
        }
        String value = field.textValue();
        if (value.isBlank()) {
            throw new InvalidMessageException("Field '" + fieldName + "' must not be blank");
        }
        validateTextCharacters(value, fieldName);
        return value;
    }

    /** PostgreSQL-Texte erlauben weder Nullzeichen noch unvollständige Unicode-Zeichen. */
    private void validateTextCharacters(String value, String fieldName) {
        String errorMessage = "Field '" + fieldName + "' contains an unsupported character";
        int length = value.length();
        for (int i = 0; i < length; i++) {
            char character = value.charAt(i);
            if (character == 0 || Character.isLowSurrogate(character)) {
                throw new InvalidMessageException(errorMessage);
            }
            if (Character.isHighSurrogate(character)) {
                i++;
                if (i == length) {
                    throw new InvalidMessageException(errorMessage);
                }
                char nextCharacter = value.charAt(i);
                if (!Character.isLowSurrogate(nextCharacter)) {
                    throw new InvalidMessageException(errorMessage);
                }
            }
        }
    }

    /** Java akzeptiert auch verkürzte UUIDs; der Vertrag verlangt die vollständige Schreibweise. */
    private UUID readUuid(JsonNode root, String fieldName) {
        String value = readText(root, fieldName);
        try {
            UUID uuid = UUID.fromString(value);
            String canonicalValue = uuid.toString();
            if (!canonicalValue.equalsIgnoreCase(value)) {
                throw new InvalidMessageException("Field '" + fieldName + "' must be a full UUID");
            }
            return uuid;
        } catch (IllegalArgumentException exception) {
            throw new InvalidMessageException("Field '" + fieldName + "' must be a UUID", exception);
        }
    }

    /** Nicht speicherbare Zeitpunkte dürfen keinen ganzen Batch dauerhaft blockieren. */
    private Instant readTimestamp(JsonNode root) {
        String value = readText(root, "sentAt");
        try {
            Instant timestamp = Instant.parse(value);
            if (timestamp.isBefore(MINIMUM_TIMESTAMP) || timestamp.isAfter(MAXIMUM_TIMESTAMP)) {
                throw new InvalidMessageException("Field 'sentAt' is outside the supported timestamp range");
            }
            return timestamp;
        } catch (DateTimeParseException exception) {
            throw new InvalidMessageException("Field 'sentAt' must be an ISO-8601 instant", exception);
        }
    }
}
