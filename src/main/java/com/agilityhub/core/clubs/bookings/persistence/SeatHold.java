package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S08 R-08-07 ephemeral seat guarantee. The TTL index removes it up to 60 s late, so every read
 * must also filter `expiresAt > now`. `accountId` owns the hold (the admin's account when impersonating).
 */
@Document("seat_holds")
public record SeatHold(@Id String id, String clubId, String classSessionId, String dogId, String memberId, String accountId,
        String waitlistEntryId, Instant createdAt, Instant expiresAt) implements TenantEntity { }
