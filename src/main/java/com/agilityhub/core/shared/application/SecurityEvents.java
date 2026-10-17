package com.agilityhub.core.shared.application;

/** Global security telemetry; independent of club audit and the domain-event outbox. */
public interface SecurityEvents {
    // S14 R-14-17 names, plus the tenant rejection required by E0-T11.
    enum Type {
        LOGIN_FAILED, LOGIN_LOCKED, MAGIC_LINK_INVALID, REFRESH_TOKEN_REUSED, RATE_LIMITED,
        HANDOFF_INVALID, IMPERSONATION_DENIED, WEBHOOK_SIGNATURE_INVALID, TENANT_MISMATCH
    }

    void record(Type type, String accountId, String clubId);

    /**
     * S14 R-14-17 (E11-T01): the same event with the address it was about — stored only as its keyed `emailHash`, never in clear —
     * and its own details (the limited route of `RATE_LIMITED`).
     */
    default void record(Type type, String accountId, String clubId, String email, java.util.Map<String, String> details) {
        record(type, accountId, clubId);
    }
}
