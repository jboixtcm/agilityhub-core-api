package com.agilityhub.core.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** S04 checkout contract, extended by S12 §6 (E8-T01); provider credentials and payment tokens are never response fields. */
public final class CheckoutContracts {
    private CheckoutContracts() { }
    /**
     * E8-T01 (S12 §6): `bookingId` (S08 R-08-18, a PAY_TO_BOOK booking) and `upfrontPaymentIds` (S12 §13-11, packs from the
     * app) extend the signup checkout; E8-T04 serves them, until then a request with either answers 501 NOT_IMPLEMENTED.
     */
    public record CheckoutSessionRequest(@NotBlank @Schema(format = "uuid") String memberId,
            @Schema(requiredMode = NOT_REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "Required for ANON: 24-hour HMAC capability bound to memberId") @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String signupToken,
            @NotBlank @Schema(format = "uri") String successUrl, @NotBlank @Schema(format = "uri") String cancelUrl,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid", description = "S08 R-08-18: the PAYMENT_PENDING booking to pay (E8-T04)") String bookingId,
            @Schema(requiredMode = NOT_REQUIRED, description = "S12 §13-11: the ids (UUID) of the member's DUE upfront payments to pay, e.g. a pack (E8-T04)")
            List<String> upfrontPaymentIds) {
        /** The S04 signup request (E3-T03): no S12 field. */
        public CheckoutSessionRequest(String memberId, String signupToken, String successUrl, String cancelUrl) {
            this(memberId, signupToken, successUrl, cancelUrl, null, null);
        }
        /** Whether the request asks for one of the S12 extensions E8-T04 serves. */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public boolean extended() { return bookingId != null || upfrontPaymentIds != null; }
    }
    public record CheckoutSession(@Schema(format = "uri") String checkoutUrl, String checkoutSessionId) { }
}
