package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.payments.api.BillingContracts.CheckoutSessionView;
import static com.agilityhub.core.payments.api.CheckoutContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
public class CheckoutController {
    private final com.agilityhub.core.payments.application.CheckoutService checkout;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.payments.application.PaymentCheckouts paymentCheckouts;
    private final BillingContractAccess access;
    public CheckoutController(com.agilityhub.core.payments.application.CheckoutService checkout, com.fasterxml.jackson.databind.ObjectMapper mapper,
            BillingContractAccess access) { this.checkout=checkout;this.mapper=mapper;this.access=access; }
    @PostMapping("/api/v1/checkout-sessions")
    @RequiresModule(Module.BILLING)
    @PreAuthorize("isAnonymous() or hasRole('MEMBER') or (hasRole('ADMIN') and principal.claims['imp'] != true)")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, UNAUTHENTICATED, NOT_FOUND, MEMBER_ERASED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED, MODULE_DISABLED,
            PAYMENT_PROVIDER_NOT_ENABLED, PROVIDER_CONFIG_INVALID, RATE_LIMITED, STALE_VERSION})
    @Operation(summary = "Create signup checkout session", description = "S04 §6, R-04-20/26. BILLING required. ANON by host with signupToken, MEMBER for self, ADMIN for tenant member. Idempotency-Key is a UUID. Anonymous limit 10/hour per club and IP from proxy-injected X-Forwarded-For. No cookies or CSRF. E3-T03 enforces capability/ownership/redirect checks, encrypted anonymous replay protection and limits. E5-T28 (A3-06): the session and its rows commit in the signup's retried transaction (a concurrent census write never gives a 500), and the provider session opens after that commit; a provider failure expires the session again. A rejection of the signup expires its open session (A3-01) and gives the rows of other submissions it charged back to DUE; a rejection that lands while the provider opens the session gives 409 INVALID_STATE and no checkoutUrl. A retry with the same Idempotency-Key after a lost answer returns the same checkout session. "
            + "E8-T01 (S12 §6): bookingId (S08 R-08-18, a PAY_TO_BOOK booking) and upfrontPaymentIds (the member's DUE rows, e.g. a pack from the app) are published; E8-T04 serves them. A request with either first passes the same role, member and club checks as the signup checkout, then checks booking and payment ownership (404 NOT_FOUND). E8-T04 opens the checkout after the local transaction commits.",
            responses = @ApiResponse(responseCode = "201", description = "Checkout session", useReturnTypeSchema = true))
    public CheckoutSession create(@io.swagger.v3.oas.annotations.Parameter(schema = @Schema(format = "uuid")) @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CheckoutSessionRequest request) {
        // E8-T04: the S12 extensions run behind the signup checkout's guards and their references' (round 2).
        if (request.extended()) {
            checkout.authorize(request.memberId(), request.signupToken());
            access.checkoutReferences(request.memberId(), request.bookingId(), request.upfrontPaymentIds());
            var result = paymentCheckouts.create(request.memberId(), request.bookingId(), request.upfrontPaymentIds(), false, request.successUrl(), request.cancelUrl(),
                    created -> body(session(created)));
            return session(result);
        }
        // A3-06: the route retries inside its own transactions (IdempotencyFilter), so the service stores the 201 after the provider call.
        var result=checkout.create(request.memberId(),request.signupToken(),request.successUrl(),request.cancelUrl(),created -> body(session(created)));
        return session(result);
    }

    @GetMapping("/api/v1/checkout-sessions/{id}")
    @RequiresModule(Module.BILLING)
    @PreAuthorize("isAnonymous() or hasRole('MEMBER') or (hasRole('ADMIN') and principal.claims['imp'] != true)")
    @AllowsImpersonation
    @SecurityRequirements
    @ContractErrors({UNAUTHENTICATED, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "checkoutSession", description = "Roles: the session's creator (S12 §6): ANON by host with the X-Signup-Token capability of "
            + "the session's member (S04 R-04-26; without it → 401 UNAUTHENTICATED), the MEMBER it charges (also the impersonation token) or an "
            + "ADMIN of the club (not impersonated); anyone else, another club's or an unknown session → 404. BILLING off → 404 MODULE_DISABLED. "
            + "The return screens (19, 06) poll it (≤ 10 s) and show «Pagament rebut» only with PAID; the payment itself arrives by webhook. "
            + "Implemented in E8-T04; the webhook determines the status.",
            responses = @ApiResponse(responseCode = "200", description = "CheckoutSessionView", useReturnTypeSchema = true))
    public CheckoutSessionView checkoutSession(@PathVariable String id,
            @RequestHeader(value = "X-Signup-Token", required = false) @Schema(description = "ANON only: the signup capability of the session's member") String signupToken) {
        access.checkoutSession(id, signupToken);
        return new CheckoutSessionView(id, paymentCheckouts.status(id));
    }
    private static CheckoutSession session(com.agilityhub.core.payments.application.CheckoutService.Result result) {
        return new CheckoutSession(result.checkoutUrl(),result.checkoutSessionId());
    }
    private byte[] body(CheckoutSession session) {
        try { return mapper.writeValueAsBytes(session); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
}
