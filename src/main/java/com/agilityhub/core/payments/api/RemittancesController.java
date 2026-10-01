package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.BillingTransactions;
import com.agilityhub.core.payments.application.RemittanceService;
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
 * S12 §6 SEPA remittances (D6 «Remeses», R-12-12, R-12-14, R-12-15), ADMIN only, module `BILLING` (E8-T03). The screen has no
 * mockup (S12 §13-12): the web builds it with the design system. No response carries the creditor's or a debtor's IBAN.
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class RemittancesController {
    static final String ROLES = BillingController.ROLES;
    static final String SERVED = BillingController.SERVED;
    private final BillingContractAccess access; private final com.agilityhub.core.shared.application.lists.ListEngine lists;
    private final RemittanceService remittances; private final BillingTransactions transactions; private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public RemittancesController(BillingContractAccess access, com.agilityhub.core.shared.application.lists.ListEngine lists, RemittanceService remittances,
            BillingTransactions transactions, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.access = access; this.lists = lists; this.remittances = remittances; this.transactions = transactions; this.mapper = mapper;
    }

    @GetMapping("/api/v1/remittances")
    @ListContract(filterable = {"period", "status"}, sortable = {"period", "creationAt"},
            columns = {"period*", "creationAt*", "requestedCollectionDate", "count*", "total*", "status*", "submittedAt"}, paged = true, searchable = false,
            fields = {"id", "period", "messageId", "creationAt", "requestedCollectionDate", "count", "total", "status", "fileAvailable", "submittedAt"})
    @ContractErrors({INVALID_FILTER, MODULE_DISABLED})
    @Operation(summary = "remittances", description = ROLES + "D6 «Remeses»: the club's remittances, universal list (CONVENCIONS_API §4), newest "
            + "creationAt first, filters period and status; no free-text search (a q → 400 INVALID_FILTER). A row: month, creation date, collection "
            + "date, invoice count, total, status, fileAvailable (the XML can be downloaded) and submittedAt. A rolled-back remittance stays listed "
            + "with its file (R-12-14). A club without SEPA_XML has none: an empty page, never an error (R-12-28)." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "RemittancePage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = RemittancePage.class))))
    public Object remittances(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.remittanceList(params);
        return lists.list("remittances", params);
    }

    @GetMapping("/api/v1/remittances/{id}")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "remittance", description = ROLES + "A remittance with its sequenceBreakdown (FRST, RCUR), xsdValidatedAt, submittedAt, "
            + "submittedByAccountId and its creditor snapshot, the IBAN masked." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "Remittance", useReturnTypeSchema = true))
    public Remittance remittance(@PathVariable String id) {
        access.remittance(id);
        return BillingViews.remittance(remittances.get(id));
    }

    @GetMapping("/api/v1/remittances/{id}/file")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "remittanceFile", description = ROLES + "[Descarrega l'XML]: a signed URL of the pain.008 file valid for five minutes (also "
            + "of a ROLLED_BACK one, whose file is kept); its download answers Content-Type application/xml and Content-Disposition attachment; "
            + "filename=\"remesa-{period}.xml\" (CONVENCIONS_API §5) and authorises itself (no bearer). Issuing the link is the file access, audited "
            + "DATA_EXPORTED. A remittance without a file → 404." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "RemittanceFile", useReturnTypeSchema = true))
    public RemittanceFile remittanceFile(@PathVariable String id) {
        access.remittance(id);
        var link = remittances.file(id);
        return new RemittanceFile(link.downloadUrl(), link.fileName(), link.expiresAt());
    }

    @PostMapping("/api/v1/remittances/{id}/submission")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED, STALE_VERSION})
    @Operation(summary = "submitRemittance", description = ROLES + "R-12-15 [Marca com a enviada al banc]: GENERATED → SUBMITTED with submittedAt "
            + "(the club-local day the XML went to the bank: not after today, not before the remittance's day → otherwise 400 VALIDATION_ERROR "
            + "{field: submittedAt}) and submittedByAccountId; REMITTANCE_SUBMITTED audit. From then on the run cannot be rolled back (409 "
            + "RUN_NOT_ROLLBACKABLE {reasons: [REMITTANCE_SUBMITTED]}) and an unpaid debit follows R-12-17. Not GENERATED → 409 INVALID_STATE. "
            + "The same Idempotency-Key answers the same remittance." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "Remittance", useReturnTypeSchema = true))
    public Remittance submitRemittance(@PathVariable String id, @Valid @RequestBody SubmissionRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.remittance(id);
        var submitted = transactions.keyed(200, () -> remittances.submit(id, request.submittedAt()), result -> json(BillingViews.remittance(result)));
        return BillingViews.remittance(submitted);
    }

    private byte[] json(Object body) {
        try { return mapper.writeValueAsBytes(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }
}
