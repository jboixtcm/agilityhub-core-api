package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class MemberPlanChangeController {
    public record MemberPlanChangeRequest(@NotBlank String planId, @NotBlank String priceId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) @Pattern(regexp="[0-9]{4}-(0[1-9]|1[0-2])") String effectiveMonth) { }
    private final MemberPlanChangeService service; private final BillingTransactions transactions;
    public MemberPlanChangeController(MemberPlanChangeService service, BillingTransactions transactions) { this.service = service; this.transactions = transactions; }
    @PostMapping("/api/v1/members/{id}/plan-change")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MEMBER_ERASED, MODULE_DISABLED, PLAN_NOT_AVAILABLE, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary="changeMemberPlan", description=BillingController.ROLES + "Changes the current plan and price without moving nextInvoiceDate. A PACK to MONTHLY change creates a DUE entry fee, discounted once per eligible pack. effectiveMonth, if supplied, must be the current club-local month.")
    public void change(@PathVariable String id, @Valid @RequestBody MemberPlanChangeRequest request, @RequestHeader("Idempotency-Key") UUID key) {
        transactions.keyed(204, () -> { service.change(id, request.planId(), request.priceId(), request.effectiveMonth()); return null; }, ignored -> new byte[0]);
    }
}
