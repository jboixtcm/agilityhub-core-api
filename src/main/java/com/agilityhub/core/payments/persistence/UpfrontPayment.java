package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * `bookingId` (model `UpfrontPayment.bookingId?`) is set only on the S08 PAY_TO_BOOK line (`concept = SINGLE_CLASS`).
 * `submissionId` (S04 §3/§5, E3-T08): the signup submission (`POST /signup` or `POST /me/dogs/signup`) that created the
 * row; only the rows of a dog's current submission belong to its signup. Rows written before E3-T08 have none.
 * `correctionOf` (S04 §5, ruling E39b): set on the `PAID` row that records what a closed `PARTIAL` row had received.
 * E8-T01 widens the row to S12 §3 (the signup flow keeps writing the first sixteen fields, through the legacy constructor;
 * its partial updates never touch the rest): `provider` stays the provider's name (`STRIPE` · `MANUAL`), its STRIPE details
 * are `checkoutSessionId` + `stripe {paymentIntentId, chargeId}`, its MANUAL ones `channel` (`CASH` · `TRANSFER` · `BIZUM`),
 * `paidAt` and `reference`; `activityRegistrationId` and `packBalanceId` link the S07 registration and the S12 pack it
 * paid; `refunds[]` (R-12-20) and the admin's `note`. `concept` and `status` take the S12 values (`UpfrontConcept`,
 * `UpfrontStatus`), stored as their names.
 */
@Document("upfront_payments")
public record UpfrontPayment(@Id String id,String clubId,String memberId,String dogId,String concept,String signupConcept,
        Money amountDue,Money amountPaid,String status,String provider,String checkoutSessionId,Instant createdAt,Instant paidAt,String bookingId,
        String submissionId,String correctionOf,String activityRegistrationId,String packBalanceId,StripeRefs stripe,String channel,String reference,
        List<Refund> refunds,String note) implements TenantEntity {
    /** The S04 signup row (E3-T03…E5-T31): no S12 field yet. */
    public UpfrontPayment(String id,String clubId,String memberId,String dogId,String concept,String signupConcept,Money amountDue,Money amountPaid,
            String status,String provider,String checkoutSessionId,Instant createdAt,Instant paidAt,String bookingId,String submissionId,String correctionOf) {
        this(id,clubId,memberId,dogId,concept,signupConcept,amountDue,amountPaid,status,provider,checkoutSessionId,createdAt,paidAt,bookingId,submissionId,
                correctionOf,null,null,null,null,null,null,null);
    }
    /** The provider's references of a paid Stripe Checkout (R-12-21): the refund goes to `chargeId` (R-12-20). */
    public record StripeRefs(String paymentIntentId, String chargeId) { }
    public record Refund(Money amount, String providerRef, Instant at, String reason, String byAccountId) { }
}
