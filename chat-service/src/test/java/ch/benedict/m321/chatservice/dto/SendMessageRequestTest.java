package ch.benedict.m321.chatservice.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Feldregeln der eingehenden Nachricht.
 * Diese Regeln sind die Grenze des Dienstes: was hier durchkommt,
 * gilt danach als gültig.
 */
class SendMessageRequestTest {

    private Validator validator;

    /** Jede Prüfung verwendet die echten Bean-Validation-Regeln des HTTP-Vertrags. */
    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        this.validator = factory.getValidator();
    }

    /** Eine vollständige Anfrage darf nicht schon an der Eingangsprüfung scheitern. */
    @Test
    void acceptsCompleteRequest() {
        var messageId = UUID.randomUUID();
        SendMessageRequest request = new SendMessageRequest(
                messageId, "anna", "Anna Muster", "Hallo zusammen");

        Set<ConstraintViolation<SendMessageRequest>> violations = validator.validate(request);

        var empty = violations.isEmpty();
        assertTrue(empty);
    }

    /** Reine Leerzeichen sind keine anzuzeigende Chat-Nachricht. */
    @Test
    void rejectsBlankContent() {
        var messageId = UUID.randomUUID();
        SendMessageRequest request = new SendMessageRequest(
                messageId, "anna", "Anna Muster", "   ");

        Set<ConstraintViolation<SendMessageRequest>> violations = validator.validate(request);

        var count = violations.size();
        assertEquals(1, count);
    }

    /** Ohne Raum könnte der Zustellweg keine eindeutige Empfängergruppe wählen. */
    @Test
    void rejectsMissingRoomId() {
        SendMessageRequest request = new SendMessageRequest(
                null, "anna", "Anna Muster", "Hallo");

        Set<ConstraintViolation<SendMessageRequest>> violations = validator.validate(request);

        var count = violations.size();
        assertEquals(1, count);
    }
}
