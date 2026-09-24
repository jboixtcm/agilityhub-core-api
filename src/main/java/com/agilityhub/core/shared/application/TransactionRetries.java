package com.agilityhub.core.shared.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metrics of the retried Mongo transactions (S07 R-07-08, S08 R-08-07, S09 R-09-06): `core.transactions.retries`
 * {context, cause} counts every retry after a `WriteConflict` / `TransientTransactionError` / `DuplicateKey`, and
 * `core.transactions.exhausted` {context} every request that ran out of attempts (answered `409 STALE_VERSION`).
 * They show in production how often the Mongo mechanisms are reached, and let the tests prove they ran.
 */
@Component
public class TransactionRetries {
    public static final String RETRIES = "core.transactions.retries", EXHAUSTED = "core.transactions.exhausted";
    private final MeterRegistry registry;

    public TransactionRetries(MeterRegistry registry) { this.registry = registry; }

    public void retried(String context, Throwable failure) {
        Counter.builder(RETRIES).tag("context", context).tag("cause", cause(failure)).register(registry).increment();
    }
    public void exhausted(String context) { Counter.builder(EXHAUSTED).tag("context", context).register(registry).increment(); }

    /** Retries of {@code context} so far, every cause together. */
    public double retries(String context) {
        return registry.find(RETRIES).tag("context", context).counters().stream().mapToDouble(Counter::count).sum();
    }
    /** Retries of {@code context} caused by {@code cause} (`write_conflict`, `duplicate_key`, `transient`). */
    public double retries(String context, String cause) {
        return registry.find(RETRIES).tag("context", context).tag("cause", cause).counters().stream().mapToDouble(Counter::count).sum();
    }
    /** Requests of {@code context} that ran out of attempts so far. */
    public double exhaustions(String context) {
        return registry.find(EXHAUSTED).tag("context", context).counters().stream().mapToDouble(Counter::count).sum();
    }

    /** A Mongo `TransientTransactionError` or `WriteConflict` anywhere in the cause chain: the whole unit of work may run again. */
    public static boolean transientFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.mongodb.MongoException mongo && (mongo.hasErrorLabel("TransientTransactionError") || mongo.getCode() == 112)) { return true; }
        }
        return false;
    }

    static String cause(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.dao.DuplicateKeyException) { return "duplicate_key"; }
            if (cause instanceof com.mongodb.MongoException mongo) {
                if (mongo.getCode() == 11000) { return "duplicate_key"; }
                if (mongo.getCode() == 112) { return "write_conflict"; }
            }
        }
        return "transient";
    }
}
