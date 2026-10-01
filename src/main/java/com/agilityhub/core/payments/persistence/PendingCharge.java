package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * S12 §3 single class charged by consumption (R-12-25): one per booking (unique `{clubId, bookingId}`), billed by the month's
 * run (`invoiceId`) or voided before (`voidedAt`, S10 R-10-07). `amount` is the `SINGLE_CLASS` price at the class date; a
 * `CREDIT` cancellation policy writes a negative one.
 */
@Document("pending_charges")
public record PendingCharge(@Id String id, String clubId, String memberId, String dogId, String bookingId, String priceId, Money amount,
        String description, Instant createdAt, String invoiceId, Instant voidedAt) implements TenantEntity { }
