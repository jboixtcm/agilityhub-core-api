package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.shared.application.TransactionRetries;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One Mongo transaction per S11 write of E7-T03 (D9's saves, 12/D10's preferences, the push devices, the test send), retried
 * whole on `DuplicateKey`, `WriteConflict` or `TransientTransactionError` — at most {@value #ATTEMPTS} attempts with the shared
 * 50–150 ms backoff, never after a commit — so two overlapping requests answer the rule's code (a stale template version is
 * `409 STALE_VERSION` when re-read), never a 500; `409 STALE_VERSION` once the attempts run out (CONVENCIONS_API §7, E73).
 * Inside an outer transaction the work joins it and the outer owner retries.
 */
@Service
public class MessagingTransactions {
    static final int ATTEMPTS = 3;
    static final String CONTEXT = "messaging";
    private final TransactionTemplate transactions; private final TransactionRetries retries;

    public MessagingTransactions(TransactionTemplate transactions, TransactionRetries retries) { this.transactions = transactions; this.retries = retries; }

    public <T> T write(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        for (int attempt = 1; ; attempt++) {
            try { return transactions.execute(tx -> work.get()); }
            catch (RuntimeException failure) {
                if (!TransactionRetries.conflict(failure)) { throw failure; }
                if (attempt >= ATTEMPTS) { retries.exhausted(CONTEXT); throw new ApiException(ErrorCode.STALE_VERSION); }
                retries.retried(CONTEXT, failure);
                try { Thread.sleep(TransactionRetries.jitter()); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
        }
    }
    public void run(Runnable work) { write(() -> { work.run(); return null; }); }
}
