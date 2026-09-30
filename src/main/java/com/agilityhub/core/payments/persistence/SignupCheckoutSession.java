package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.application.PaymentProvider;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A provider checkout: the signup one (S04) or, with `bookingId`, the payment of an S08 PAY_TO_BOOK booking.
 * `providerPaymentId` is the provider's payment of a completed session. `lateCompletionAt` is the E34 reconciliation mark:
 * the provider completed a checkout after it expired on our side, after its booking was cancelled, or after its signup rows
 * were closed, so S12 (E8-T04 step 12) refunds `providerPaymentId`. `requestRef` names the keyed `POST /checkout-sessions`
 * that opened a signup session (a digest of its scope, key and body, E5-T28): a retry of that request whose answer was lost
 * finds it again. `providerRequest` is what that request sent the provider (E5-T30): the retry sends exactly the same, so
 * an edit of the member in between never reaches the provider under the same `sessionId`. It is kept only while the session
 * is `PENDING` and its answer is not stored: the stored answer and the session's end drop it (the customer's e-mail with it).
 */
@Document("checkout_sessions")
public record SignupCheckoutSession(@Id String id,String clubId,String memberId,String status,String mode,List<String> upfrontPaymentIds,Instant expiresAt,String bookingId,
        String providerPaymentId,Instant lateCompletionAt,String requestRef,PaymentProvider.Request providerRequest) implements TenantEntity {
    public SignupCheckoutSession(String id,String clubId,String memberId,String status,String mode,List<String> upfrontPaymentIds,Instant expiresAt,String bookingId) {
        this(id,clubId,memberId,status,mode,upfrontPaymentIds,expiresAt,bookingId,null,null,null,null);
    }
}
