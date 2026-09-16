package com.agilityhub.core.clubs.scheduling.application;

import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import com.agilityhub.core.shared.domain.*;

/** Retry aborted Mongo transactions, never a committed business operation or external side effect. */
@Service
public class SchedulingTransactions {
    private final TransactionTemplate transactions;
    private final PlanningContext context;
    public SchedulingTransactions(TransactionTemplate transactions, PlanningContext context) { this.transactions = transactions; this.context = context; }
    public <T> T write(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try { return transactions.execute(tx -> { context.lockReferences(); return action.get(); }); }
            catch (RuntimeException failure) {
                boolean conflict = false;
                for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                    if (cause instanceof com.mongodb.MongoException mongo && (mongo.getCode() == 112 || mongo.hasErrorLabel("TransientTransactionError"))) { conflict = true; }
                }
                if (!conflict) { throw failure; }
                if (attempt >= 5) { throw new ApiException(ErrorCode.STALE_VERSION); }
            }
        }
    }
}
