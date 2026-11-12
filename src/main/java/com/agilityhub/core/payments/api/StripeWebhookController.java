package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.StripeWebhookSignatures;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.domain.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S12 R-12-21 Stripe webhook of a club (`POST /webhooks/stripe/{clubId}`, CONVENCIONS_API §3, outside `/api/v1` like the
 * SendGrid one): no JWT and no tenant from the host — the path names the club, and the `Stripe-Signature` header signed with
 * that club's webhook secret authenticates the call (security scheme `stripeSignature`). E8-T01 round 2: the body is
 * authenticated before anything else ({@link StripeWebhookSignatures}: an unknown club, a club without a secret, a missing,
 * wrong or stale signature or a tampered body → `401 WEBHOOK_SIGNATURE_INVALID` + a `SecurityEvent`); then the club needs
 * `BILLING` (404 MODULE_DISABLED) and an enabled `STRIPE` provider (404). The event is durably stored before transactional processing.
 */
@RestController
public class StripeWebhookController {
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.payments.application.StripeWebhooks webhooks;
    private final BillingContractAccess access;
    private final StripeWebhookSignatures signatures;
    public StripeWebhookController(BillingContractAccess access, StripeWebhookSignatures signatures) { this.access = access; this.signatures = signatures; }

    @PostMapping(value = "/webhooks/stripe/{clubId}", consumes = "application/json")
    @SecurityRequirement(name = "stripeSignature")
    @ContractErrors({VALIDATION_ERROR, WEBHOOK_SIGNATURE_INVALID, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "receiveStripeEvent", description = "Roles: Stripe (signature), no bearer token. R-12-21: before anything else the "
            + "Stripe-Signature header (t={unix seconds},v1={hex HMAC-SHA256 of \"{t}.{raw body}\"}) is verified with the club's webhook secret, "
            + "with Stripe's 5-minute tolerance on t; a missing, wrong or stale signature, a tampered body, an unknown club or a club without a "
            + "webhook secret → 401 WEBHOOK_SIGNATURE_INVALID and a SecurityEvent, nothing else stored. Then a club without BILLING → 404 "
            + "MODULE_DISABLED, without an enabled STRIPE provider → 404. The event is stored once per eventId (a second delivery → 200 with no "
            + "effect) and processed in a transaction by type (checkout.session.completed/expired, payment_intent.succeeded/payment_failed, "
            + "charge.refunded, setup_intent.succeeded, payment_method.detached, customer.deleted); an out-of-order event is IGNORED. 2xx as soon "
            + "as the event is stored. Durable receipt, transactional processing and internal recovery (E8-T04).",
            responses = @ApiResponse(responseCode = "200", description = "Stored (processed, ignored or deferred)", content = @Content))
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
            schema = @Schema(implementation = BillingRequests.StripeWebhookEvent.class)))
    public void receiveStripeEvent(@PathVariable String clubId,
            @RequestHeader(value = "Stripe-Signature", required = false) @Schema(description = "t=…,v1=… (Stripe's HMAC-SHA256 of the raw body)") String signature,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) HttpServletRequest request) {
        byte[] body = body(request);
        if (body.length > MAX_BODY_BYTES) { throw new ApiException(VALIDATION_ERROR); }
        signatures.authenticate(clubId, signature, body);
        access.stripeClub(clubId);
        webhooks.receive(clubId, body);
    }

    /**
     * CFG-03: at most this many bytes of a webhook body are read, the cap of a keyed request (`IdempotencyFilter`) and of Brevo's
     * webhook; a longer body is refused before its signature is checked, so an anonymous caller cannot make the server buffer it.
     */
    static final int MAX_BODY_BYTES = 1_048_576;

    /** Reads at most one byte past {@link #MAX_BODY_BYTES}; an empty or unreadable body → 400, as Spring's required body was. */
    static byte[] body(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length == 0) { throw new ApiException(VALIDATION_ERROR); }
            return body;
        } catch (IOException unreadable) { throw new ApiException(VALIDATION_ERROR); }
    }
}
