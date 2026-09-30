package com.agilityhub.core.payments.api;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.payments.api.CheckoutContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
public class CheckoutController {
    private final com.agilityhub.core.payments.application.CheckoutService checkout;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public CheckoutController(com.agilityhub.core.payments.application.CheckoutService checkout, com.fasterxml.jackson.databind.ObjectMapper mapper) { this.checkout=checkout;this.mapper=mapper; }
    @PostMapping("/api/v1/checkout-sessions")
    @RequiresModule(Module.BILLING)
    @PreAuthorize("isAnonymous() or hasRole('MEMBER') or (hasRole('ADMIN') and principal.claims['imp'] != true)")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, UNAUTHENTICATED, NOT_FOUND, MEMBER_ERASED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED, MODULE_DISABLED,
            PAYMENT_PROVIDER_NOT_ENABLED, RATE_LIMITED, STALE_VERSION})
    @Operation(summary = "Create signup checkout session", description = "S04 §6, R-04-20/26. BILLING required. ANON by host with signupToken, MEMBER for self, ADMIN for tenant member. Idempotency-Key is a UUID. Anonymous limit 10/hour per club and IP from proxy-injected X-Forwarded-For. No cookies or CSRF. E3-T03 enforces capability/ownership/redirect checks, encrypted anonymous replay protection and limits. E5-T28 (A3-06): the session and its rows commit in the signup's retried transaction (a concurrent census write never gives a 500), and the provider session opens after that commit; a provider failure expires the session again. A rejection of the signup expires its open session (A3-01) and gives the rows of other submissions it charged back to DUE; a rejection that lands while the provider opens the session gives 409 INVALID_STATE and no checkoutUrl. A retry with the same Idempotency-Key after a lost answer returns the same checkout session.",
            responses = @ApiResponse(responseCode = "201", description = "Checkout session", useReturnTypeSchema = true))
    public CheckoutSession create(@io.swagger.v3.oas.annotations.Parameter(schema = @Schema(format = "uuid")) @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CheckoutSessionRequest request) {
        // A3-06: the route retries inside its own transactions (IdempotencyFilter), so the service stores the 201 after the provider call.
        var result=checkout.create(request.memberId(),request.signupToken(),request.successUrl(),request.cancelUrl(),created -> body(session(created)));
        return session(result);
    }
    private static CheckoutSession session(com.agilityhub.core.payments.application.CheckoutService.Result result) {
        return new CheckoutSession(result.checkoutUrl(),result.checkoutSessionId());
    }
    private byte[] body(CheckoutSession session) {
        try { return mapper.writeValueAsBytes(session); }
        catch(com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
    }
}
