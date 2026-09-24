package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** `bookingId` (model `UpfrontPayment.bookingId?`) is set only on the S08 PAY_TO_BOOK line (`concept = SINGLE_CLASS`). */
@Document("upfront_payments")
public record UpfrontPayment(@Id String id,String clubId,String memberId,String dogId,String concept,String signupConcept,
        Money amountDue,Money amountPaid,String status,String provider,String checkoutSessionId,Instant createdAt,Instant paidAt,String bookingId) implements TenantEntity { }
