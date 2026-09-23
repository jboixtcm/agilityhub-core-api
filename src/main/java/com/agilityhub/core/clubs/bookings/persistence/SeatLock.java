package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** S08 R-08-07 per-class serialisation document: `_id` is the class session id; `$inc version` makes concurrent writers conflict. */
@Document("seat_locks")
public record SeatLock(@Id String id, String clubId, long version) implements TenantEntity { }
