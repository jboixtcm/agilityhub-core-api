package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.payments.api.BillingContracts.*;
import static com.agilityhub.core.payments.api.BillingRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S12 §6 billing routes of a member's D10 card (R-12-22, R-12-25), ADMIN only, module `BILLING`. The pending charges are served
 * (E8-T02); the card-setup link runs its guards and answers 501 NOT_IMPLEMENTED until E8-T04.
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class MemberBillingController {
    static final String ROLES = BillingController.ROLES;
    static final String STUB = BillingController.STUB;
    private final BillingContractAccess access; private final com.agilityhub.core.payments.application.PendingChargeService charges;
    public MemberBillingController(BillingContractAccess access, com.agilityhub.core.payments.application.PendingChargeService charges) {
        this.access = access; this.charges = charges;
    }

    @PostMapping("/api/v1/members/{id}/card-setup-link")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, MEMBER_ERASED, IDEMPOTENCY_KEY_REUSED, PAYMENT_PROVIDER_NOT_ENABLED})
    @Operation(summary = "cardSetupLink", description = ROLES + "R-12-22: a Stripe Checkout session in mode=setup for the member (D10 «Targeta "
            + "no vàlida»): the admin sends the link; setup_intent.succeeded saves the card and sends N-38. STRIPE not enabled → 422 "
            + "PAYMENT_PROVIDER_NOT_ENABLED; another club's member → 404; an erased one → 409 MEMBER_ERASED." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "CardSetupLink", useReturnTypeSchema = true))
    public CardSetupLink cardSetupLink(@PathVariable String id, @Valid @RequestBody CardSetupRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.mutableMember(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/members/{id}/pending-charges")
    @RequiresModule(Module.SINGLE_CLASS)
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "pendingCharges", description = ROLES + "SINGLE_CLASS off → 404 MODULE_DISABLED. R-12-25: the member's single classes "
            + "charged by consumption (CHARGE_ON_ATTENDANCE: attended, no-show or cancelled late), newest first, billed (invoiceId) or not yet, voided "
            + "ones included. Another club's member → 404." + BillingController.SERVED,
            responses = @ApiResponse(responseCode = "200", description = "PendingCharge[]", useReturnTypeSchema = true))
    public List<PendingCharge> pendingCharges(@PathVariable String id) {
        access.member(id);
        return charges.forMember(id).stream().map(BillingViews::pendingCharge).toList();
    }
}
