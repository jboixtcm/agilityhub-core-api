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
    @PostMapping("/api/v1/checkout-sessions")
    @RequiresModule(Module.BILLING)
    @PreAuthorize("isAnonymous() or hasRole('MEMBER') or (hasRole('ADMIN') and principal.claims['imp'] != true)")
    @SecurityRequirements
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({UNAUTHENTICATED, NOT_FOUND, INVALID_STATE, PAYMENT_PROVIDER_NOT_ENABLED, RATE_LIMITED})
    @Operation(summary = "Create signup checkout session", description = "S04 §6, R-04-20/26. BILLING required. ANON by host with signupToken, MEMBER for self, ADMIN for tenant member. Idempotency-Key is a UUID. Anonymous limit 10/hour per club and IP from proxy-injected X-Forwarded-For. No cookies or CSRF. E3-T03 implements capability/ownership/redirect checks, anonymous replay protection and limits; standard 501 stub.",
            responses = @ApiResponse(responseCode = "201", description = "Checkout session", useReturnTypeSchema = true))
    public CheckoutSession create(@io.swagger.v3.oas.annotations.Parameter(schema = @Schema(format = "uuid")) @RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CheckoutSessionRequest request) { throw new UnsupportedOperationException(); }
}
