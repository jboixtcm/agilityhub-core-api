package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * `bookingId` (model `UpfrontPayment.bookingId?`) is set only on the S08 PAY_TO_BOOK line (`concept = SINGLE_CLASS`).
 * `submissionId` (S04 §3/§5, E3-T08): the signup submission (`POST /signup` or `POST /me/dogs/signup`) that created the
 * row; only the rows of a dog's current submission belong to its signup. Rows written before E3-T08 have none.
 * `correctionOf` (S04 §5, ruling E39b): set on the `PAID` row that records what a closed `PARTIAL` row had received.
 */
@Document("upfront_payments")
public record UpfrontPayment(@Id String id,String clubId,String memberId,String dogId,String concept,String signupConcept,
        Money amountDue,Money amountPaid,String status,String provider,String checkoutSessionId,Instant createdAt,Instant paidAt,String bookingId,
        String submissionId,String correctionOf) implements TenantEntity { }
