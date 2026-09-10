package com.agilityhub.core.identity.application;

import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Mongo write conflicts retry the complete unit of work; external side effects stay outside it. */
@Component
public class IdentityTransactions {
    private final TransactionTemplate transactions;
    public IdentityTransactions(PlatformTransactionManager manager) { transactions = new TransactionTemplate(manager); }
    public <T> T run(Supplier<T> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) { return work.get(); }
        for (int attempt = 0; ; attempt++) {
            try { return transactions.execute(status -> work.get()); }
            catch (RuntimeException failure) {
                if (attempt >= 40 || !retryable(failure) || Thread.currentThread().isInterrupted()) { throw failure; }
                // Let the winning transaction commit before rebuilding this transaction's snapshot.
                java.util.concurrent.locks.LockSupport.parkNanos(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(java.util.concurrent.ThreadLocalRandom.current().nextLong(25L, Math.min(500L, 50L << Math.min(attempt, 4)))));
            }
        }
    }
    private boolean retryable(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.mongodb.MongoException mongo && mongo.hasErrorLabel("TransientTransactionError")) { return true; }
            if (cause instanceof org.springframework.dao.DuplicateKeyException) { return true; }
        }
        return false;
    }
}
