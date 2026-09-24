package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A provider checkout: the signup one (S04) or, with `bookingId`, the payment of an S08 PAY_TO_BOOK booking.
 * `providerPaymentId` is the provider's payment of a completed session. `lateCompletionAt` is the E34 reconciliation mark:
 * the provider completed a booking checkout after it expired on our side, or after its booking was cancelled, so S12
 * (E8-T04 step 12) refunds `providerPaymentId`.
 */
@Document("checkout_sessions")
public record SignupCheckoutSession(@Id String id,String clubId,String memberId,String status,String mode,List<String> upfrontPaymentIds,Instant expiresAt,String bookingId,
        String providerPaymentId,Instant lateCompletionAt) implements TenantEntity {
    public SignupCheckoutSession(String id,String clubId,String memberId,String status,String mode,List<String> upfrontPaymentIds,Instant expiresAt,String bookingId) {
        this(id,clubId,memberId,status,mode,upfrontPaymentIds,expiresAt,bookingId,null,null);
    }
}
