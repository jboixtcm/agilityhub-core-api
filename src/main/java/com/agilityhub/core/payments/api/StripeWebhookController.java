package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S12 R-12-21 Stripe webhook of a club (`POST /webhooks/stripe/{clubId}`, CONVENCIONS_API §3, outside `/api/v1` like the
 * SendGrid one): no JWT and no tenant from the host — the path names the club, and the `Stripe-Signature` header signed with
 * that club's webhook secret authenticates the call (security scheme `stripeSignature`). The guards that exist without
 * E8-T04 run here: the club exists with `BILLING` and an enabled `STRIPE` provider (404 otherwise), a missing signature is
 * `401 WEBHOOK_SIGNATURE_INVALID` + a `SecurityEvent` (S14 R-14-17); then 501 NOT_IMPLEMENTED until E8-T04 verifies the
 * signature, stores the `StripeEvent` once per `eventId` and processes it.
 */
@RestController
public class StripeWebhookController {
    private final BillingContractAccess access;
    private final SecurityEvents securityEvents;
    public StripeWebhookController(BillingContractAccess access, SecurityEvents securityEvents) { this.access = access; this.securityEvents = securityEvents; }

    @PostMapping(value = "/webhooks/stripe/{clubId}", consumes = "application/json")
    @SecurityRequirement(name = "stripeSignature")
    @ContractErrors({VALIDATION_ERROR, WEBHOOK_SIGNATURE_INVALID, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "receiveStripeEvent", description = "Roles: Stripe (signature), no bearer token. R-12-21: the Stripe-Signature header is "
            + "verified with the club's webhookSecret (invalid or missing → 401 WEBHOOK_SIGNATURE_INVALID and a SecurityEvent); the event is stored "
            + "once per eventId (a second delivery → 200 with no effect) and processed in a transaction by type (checkout.session.completed/expired, "
            + "payment_intent.succeeded/payment_failed, charge.refunded, setup_intent.succeeded, payment_method.detached, customer.deleted); an "
            + "out-of-order event is IGNORED. 2xx as soon as the event is stored. An unknown club, or one without BILLING (MODULE_DISABLED) or "
            + "without an enabled STRIPE provider → 404. Contract only; returns 501 NOT_IMPLEMENTED after the club and signature-presence guards (E8-T01).",
            responses = @ApiResponse(responseCode = "200", description = "Stored (processed, ignored or deferred)", content = @Content))
    public void receiveStripeEvent(@PathVariable String clubId,
            @RequestHeader(value = "Stripe-Signature", required = false) @Schema(description = "t=…,v1=… (Stripe's HMAC-SHA256 of the raw body)") String signature,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
                    schema = @Schema(implementation = BillingRequests.StripeWebhookEvent.class))) @RequestBody byte[] body) {
        access.stripeClub(clubId);
        if (signature == null || signature.isBlank()) {
            securityEvents.record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, clubId);
            throw new ApiException(ErrorCode.WEBHOOK_SIGNATURE_INVALID);
        }
        throw new UnsupportedOperationException();
    }
}
