package com.agilityhub.core.shared.application;

/** Global security telemetry; independent of club audit and the domain-event outbox. */
public interface SecurityEvents {
    // S14 R-14-17 names, plus the tenant rejection required by E0-T11.
    enum Type {
        LOGIN_FAILED, LOGIN_LOCKED, MAGIC_LINK_INVALID, REFRESH_TOKEN_REUSED, RATE_LIMITED,
        IMPERSONATION_DENIED, WEBHOOK_SIGNATURE_INVALID, TENANT_MISMATCH
    }

    void record(Type type, String accountId, String clubId);
}
