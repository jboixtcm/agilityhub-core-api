package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S08 R-08-07 per-class serialisation document: `_id` is the class session id; `$inc version` makes concurrent writers conflict.
 * Ephemeral (S08 §3, INC-40 E11-T10): every lock stamps `lockedAt`, and the TTL `seat_lock_ttl` drops a lock no write touched for
 * {@link SeatLockRepository#TTL}; nothing reads `version`, and the next lock recreates the document.
 */
@Document("seat_locks")
public record SeatLock(@Id String id, String clubId, long version, Instant lockedAt) implements TenantEntity { }
