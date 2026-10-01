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
 * S12 §6 pack balances of D10 (R-12-23, R-12-24), ADMIN only, modules `BILLING` and `PACKS` (off → 404). Every operation runs
 * its guards and then answers 501 NOT_IMPLEMENTED until E8-T05.
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class PackBalancesController {
    static final String ROLES = BillingController.ROLES + "PACKS off → 404 MODULE_DISABLED. ";
    static final String STUB = BillingController.STUB;
    private final BillingContractAccess access;
    public PackBalancesController(BillingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/pack-balances")
    @RequiresModule(Module.PACKS)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "packBalances", description = ROLES + "D10 «Pack {n} — {consumides} consumides · {disponibles} disponibles · caduca el "
            + "{data}»: the packs of a member (memberId) or of a dog (dogId), at least one of the two; with their movements. Another club's member "
            + "or dog → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "PackBalanceDetail[]", useReturnTypeSchema = true))
    public List<PackBalanceDetail> packBalances(@RequestParam(required = false) @Schema(format = "uuid") String memberId,
            @RequestParam(required = false) @Schema(format = "uuid") String dogId) {
        if (memberId == null && dogId == null) { throw BillingContractAccess.invalid("memberId"); }
        if (memberId != null) { access.member(memberId); }
        if (dogId != null) { access.dog(dogId); }
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/pack-balances")
    @RequiresModule(Module.PACKS)
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, MEMBER_ERASED, IDEMPOTENCY_KEY_REUSED, PLAN_NOT_PACK})
    @Operation(summary = "openPackBalance", description = ROLES + "A pack opened by hand (migration, a gift): sessionsTotal defaults to the "
            + "plan's pack.sessions and expiresOn to openedOn + validityMonths − 1 day (R-12-23); PackOpened. A plan that is not a pack → 422 "
            + "PLAN_NOT_PACK; another club's member or dog → 404; an erased member → 409 MEMBER_ERASED." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "PackBalanceDetail", useReturnTypeSchema = true))
    public PackBalanceDetail openPackBalance(@Valid @RequestBody PackBalanceRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.mutableMember(request.memberId());
        access.dog(request.dogId());
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/pack-balances/{id}/adjustments")
    @RequiresModule(Module.PACKS)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, IDEMPOTENCY_KEY_REUSED, PACK_NEGATIVE})
    @Operation(summary = "adjustPackBalance", description = ROLES + "R-12-24 [Ajusta]: an ADJUST movement of delta sessions with its reason; it may "
            + "reopen an EXPIRED pack with a new expiresOn (required then). Below zero → 422 PACK_NEGATIVE. PackAdjusted, PACK_ADJUSTED audit." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "PackBalanceDetail", useReturnTypeSchema = true))
    public PackBalanceDetail adjustPackBalance(@PathVariable String id, @Valid @RequestBody PackAdjustmentRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.pack(id);
        throw new UnsupportedOperationException();
    }
}
