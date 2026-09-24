package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S09 R-09-13 technical serialisation document of one ring on one training grid slot: `_id = clubId:ringId:startsAt`.
 * A training booking `$inc`s the `sequence` of its own slot; every write that checks the ring's training bookings
 * (S06 ring block and class moved onto the ring, S07 activity block, S05 ring no longer reservable) `$inc`s each grid
 * slot its range overlaps, in its transaction. The two sides of the conflict share a document, so Mongo raises a
 * `WriteConflict` instead of letting both commit (write skew), while bookings of different slots never meet (E5-T07
 * round 2: one document per ring-day made 15 of 20 bookings on 20 slots of one day exhaust their retries).
 */
@Document("ring_slot_locks")
public record RingSlotLock(@Id String id, String clubId, String ringId, Instant startsAt, long sequence) implements TenantEntity { }
