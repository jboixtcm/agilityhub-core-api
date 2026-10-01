package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.payments.api.BillingContracts.*;
import static com.agilityhub.core.payments.api.BillingRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S12 §6 SEPA remittances (D6 «Remeses», R-12-12, R-12-15), ADMIN only, module `BILLING`. Every operation runs its guards and
 * then answers 501 NOT_IMPLEMENTED until E8-T03.
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class RemittancesController {
    static final String ROLES = BillingController.ROLES;
    static final String STUB = BillingController.STUB;
    private final BillingContractAccess access;
    public RemittancesController(BillingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/remittances")
    @ListContract(filterable = {"period", "status"}, sortable = {"period", "creationAt"},
            columns = {"period*", "creationAt*", "requestedCollectionDate", "count*", "total*", "status*", "submittedAt"}, paged = true, searchable = false,
            fields = {"id", "period", "messageId", "creationAt", "requestedCollectionDate", "count", "total", "status", "fileAvailable", "submittedAt"})
    @ContractErrors({INVALID_FILTER, MODULE_DISABLED})
    @Operation(summary = "remittances", description = ROLES + "D6 «Remeses»: the club's remittances, universal list (CONVENCIONS_API §4), newest "
            + "creationAt first, filters period and status; no free-text search (a q → 400 INVALID_FILTER)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "RemittancePage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RemittancePage.class))))
    public Object remittances(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.remittanceList(params);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/remittances/{id}")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "remittance", description = ROLES + "A remittance with its creditor snapshot (IBAN masked). Another club's → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Remittance", useReturnTypeSchema = true))
    public Remittance remittance(@PathVariable String id) {
        access.remittance(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/remittances/{id}/file")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "remittanceFile", description = ROLES + "[Descarrega l'XML]: a short-lived signed URL of the pain.008 file (also of a "
            + "ROLLED_BACK one); its download answers Content-Disposition attachment (CONVENCIONS_API §5). Another club's → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "RemittanceFile", useReturnTypeSchema = true))
    public RemittanceFile remittanceFile(@PathVariable String id) {
        access.remittance(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/remittances/{id}/submission")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "submitRemittance", description = ROLES + "R-12-15 [Marca com a enviada al banc]: GENERATED → SUBMITTED with submittedAt; "
            + "from then on the run cannot be rolled back and unpaid ones follow R-12-17. REMITTANCE_SUBMITTED audit. Not GENERATED → 409 INVALID_STATE." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Remittance", useReturnTypeSchema = true))
    public Remittance submitRemittance(@PathVariable String id, @Valid @RequestBody SubmissionRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.remittance(id);
        throw new UnsupportedOperationException();
    }
}
