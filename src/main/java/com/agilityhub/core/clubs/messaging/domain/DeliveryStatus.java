package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The status of one delivery (S11 §5): the six of CATALEG_NOTIFICACIONS rule 7 plus `SKIPPED_NO_CONTACT`, `SKIPPED_CAP`
 * and `SKIPPED_STALE`, which S11 §13 proposes (implemented here; the catalog proposal is in the E7-T01 report).
 */
@Schema(enumAsRef = true)
public enum DeliveryStatus {
    QUEUED, SENT, DELIVERED, FAILED, SKIPPED_BY_PREFERENCE, SKIPPED_MODULE_OFF, SKIPPED_NO_CONTACT, SKIPPED_CAP, SKIPPED_STALE;

    /** Whether the delivery reached the provider or the feed (the feed's «i per SMS» counts SMS in these states only, R-11-10). */
    public boolean reached() { return this == SENT || this == DELIVERED; }
}
