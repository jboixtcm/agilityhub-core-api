package com.agilityhub.core.shared.application;

import java.time.Instant;

/**
 * OUT-01 (owner decision of 13 Nov 2026, CATALEG_ESDEVENIMENTS rule 2): told once by the {@link OutboxDispatcher}, right after
 * an outbox record has ended `FAILED` (its retries spent, about a day), outside any transaction and without the event's payload.
 * The platform's admins are alerted through it (N-57, `identity`'s implementation). It is a direct call, not another outbox
 * event: an alert about the outbox does not wait in the outbox. An implementation must not throw for a delivery failure; one
 * that does is logged and the record stays `FAILED`.
 */
public interface OutboxFailureAlerts {
    void failed(FailedEvent event);

    /** The record that ended `FAILED`: ids, type and the persisted error type only (never its payload). */
    record FailedEvent(String id, String clubId, String type, String aggregateType, int attempts, String error, Instant failedAt) { }
}
