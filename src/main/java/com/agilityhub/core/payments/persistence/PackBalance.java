package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.PackBalanceState;
import com.agilityhub.core.payments.domain.PackMovementType;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.domain.audit.AuditField;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 pack balance (`CONSUM_PACK`), owned by `payments` (the glossary's `clubs/billing` package does not exist: deviation
 * recorded by E8-T01). Movements are append-only (R-12-10). `remaining` is derived (`sessionsTotal − consumed` plus the
 * adjustments): it is kept beside the movements, in the same write as each one, only so that R-12-24's «an ACTIVE pack with
 * `remaining > 0`, the one that expires first» is one indexed query; the API never accepts it. `openedOn`/`expiresOn` are
 * club-local `YYYY-MM-DD` (`expiresOn = openedOn + validityMonths − 1 day`, R-12-23). `{clubId, upfrontPaymentId}` is
 * unique when present: a payment never opens two packs.
 */
@Document("pack_balances")
public record PackBalance(@Id String id, String clubId, String memberId, String dogId, String planId, String upfrontPaymentId,
        int sessionsTotal, int consumed, @AuditField int remaining, String openedOn, @AuditField String expiresOn, @AuditField PackBalanceState state,
        List<Movement> movements, Instant expiryWarnedAt, Instant expiredAt, Instant lowBalanceNotifiedAt, Map<String, Object> sourceIds,
        @Version Long version, Instant createdAt, String createdByAccountId) implements TenantEntity {
    public record Movement(String id, PackMovementType type, int delta, String bookingId, String reason, String byAccountId, Instant at) { }
}
