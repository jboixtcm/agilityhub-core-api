package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S09 R-09-13 technical serialisation document of one ring on one club-local day: `_id = clubId:ringId:date`. A training
 * booking and every S06 write that checks the ring's training bookings (ring block, class moved onto the ring) `$inc`
 * its `sequence` in their transaction, so the two sides of the conflict share a document and Mongo raises a
 * `WriteConflict` instead of letting both commit (write skew).
 */
@Document("ring_day_locks")
public record RingDayLock(@Id String id, String clubId, String ringId, String date, long sequence) implements TenantEntity { }
