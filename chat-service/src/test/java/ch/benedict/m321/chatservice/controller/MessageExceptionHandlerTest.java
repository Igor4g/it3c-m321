package ch.benedict.m321.chatservice.controller;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Ist RabbitMQ nicht erreichbar, darf der Benutzer keine 500 sehen und
 * schon gar kein stilles "ok". 503 heisst: nimm es nochmal, später.
 */
class MessageExceptionHandlerTest {

    /** Ein Broker-Fehler wird als vorübergehende Nichtverfügbarkeit erkennbar. */
    @Test
    void answersWithServiceUnavailable() {
        MessageExceptionHandler handler = new MessageExceptionHandler();
        AmqpException exception = new AmqpException("Broker nicht erreichbar");

        ResponseEntity<String> response = handler.handleBrokerNotAvailable(exception);

        var getStatusCodeResult = response.getStatusCode();
        var valueResult = getStatusCodeResult.value();
        assertEquals(503, valueResult);
        var getBodyResult = response.getBody();
        assertNotNull(getBodyResult);
    }
}
