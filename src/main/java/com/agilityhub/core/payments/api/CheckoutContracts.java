package com.agilityhub.core.payments.api;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/** S04 checkout contract; provider credentials and payment tokens are never response fields. */
public final class CheckoutContracts {
    private CheckoutContracts() { }
    public record CheckoutSessionRequest(@NotBlank @Schema(format = "uuid") String memberId,
            @Schema(requiredMode = NOT_REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY,
                    description = "Required for ANON: 24-hour HMAC capability bound to memberId") @com.fasterxml.jackson.annotation.JsonProperty(access = com.fasterxml.jackson.annotation.JsonProperty.Access.WRITE_ONLY) String signupToken,
            @NotBlank @Schema(format = "uri") String successUrl, @NotBlank @Schema(format = "uri") String cancelUrl) { }
    public record CheckoutSession(@Schema(format = "uri") String checkoutUrl, String checkoutSessionId) { }
}
