package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.ChatMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/** Besitzt die Transaktionsgrenze, damit der Consumer erst nach dem COMMIT bestätigen kann. */
@Service
@RequiredArgsConstructor
public class BatchWriteService {

    private final MessageRepository messageRepository;
    private final TransactionTemplate transactionTemplate;

    /** Speichert einen nicht leeren Batch vollständig oder wirft nach dem Rollback einen Fehler. */
    public void saveBatch(List<ChatMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }

        // executeWithoutResult kehrt erst nach COMMIT zurück; Fehler gehen an den Consumer weiter.
        transactionTemplate.executeWithoutResult(status -> messageRepository.saveBatch(messages));
    }
}
