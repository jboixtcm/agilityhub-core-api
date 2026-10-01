package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.BillingQueries;
import com.agilityhub.core.payments.application.BillingRunService;
import com.agilityhub.core.payments.application.BillingSimulationService;
import com.agilityhub.core.payments.application.BillingTransactions;
import com.agilityhub.core.payments.application.ports.CardChargingPort;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ApiContracts.ExportAccepted;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.time.YearMonth;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.payments.api.BillingContracts.*;
import static com.agilityhub.core.payments.api.BillingRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S12 §6 monthly cycle of D6 (R-12-07…14, R-12-26), ADMIN only, module `BILLING`; the impersonation token is refused
 * (`IMPERSONATION_DENIED`) and MEMBER/INSTRUCTOR get 403 (MATRIU «Facturació»). E8-T02 serves the month, the simulation,
 * the run and its rollback; the card charges go to `CardChargingPort` (its null object answers 422 until E8-T04) and the
 * accounting export stays 501 until E8-T06. Error statuses are CATALEG_ERRORS' (rule 0), whatever S12 §6 writes.
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class BillingController {
    static final String ADMIN = "hasRole('ADMIN') and principal.claims['imp'] != true";
    static final String ROLES = "Roles: ADMIN (MEMBER, INSTRUCTOR → 403; impersonation → 403 IMPERSONATION_DENIED). BILLING off → 404 MODULE_DISABLED. ";
    static final String STUB = " Contract only; returns 501 NOT_IMPLEMENTED after the tenant, role, module and resource guards (E8-T01). Tenant comes from the JWT.";
    static final String SERVED = " Tenant comes from the JWT; another club's resource → 404.";
    private final BillingContractAccess access; private final BillingQueries queries; private final BillingSimulationService simulations;
    private final BillingRunService runs; private final BillingTransactions transactions; private final CardChargingPort cards;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public BillingController(BillingContractAccess access, BillingQueries queries, BillingSimulationService simulations, BillingRunService runs,
            BillingTransactions transactions, CardChargingPort cards, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.access = access; this.queries = queries; this.simulations = simulations; this.runs = runs; this.transactions = transactions;
        this.cards = cards; this.mapper = mapper;
    }

    @GetMapping("/api/v1/billing/periods/{period}")
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED})
    @Operation(summary = "billingPeriod", description = ROLES + "D6's month (`?mes=YYYY-MM`): its last simulation (step 1), its live run — else its "
            + "last rolled-back one — with rollbackable and rollbackBlockers computed now (R-12-14), its remittance and the chip counts (all, "
            + "pending = PENDING, remitted = COLLECTING, paid, failed). A month is not a stored resource: every club reads its own, null parts until "
            + "they exist. period not YYYY-MM → 400 VALIDATION_ERROR." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "BillingPeriod", useReturnTypeSchema = true))
    public BillingPeriod billingPeriod(@PathVariable @Schema(pattern = BillingContracts.MONTH) String period) {
        access.tenant();
        access.month("period", period);
        return BillingViews.period(queries.period(YearMonth.parse(period)));
    }

    @PostMapping("/api/v1/billing/simulations")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, BILLING_BUSY})
    @Operation(summary = "simulateBilling", description = ROLES + "R-12-07 «1 · SIMULA EL MES»: computes R-12-01…05 without writing business data "
            + "(incidents NO_BANK_ACCOUNT, NO_PLAN, NO_PRICE, CARD_INVALID, CURRENCY_MISMATCH, PROVIDER_DISABLED; cash members with their planned "
            + "leave; preview and KPIs) and keeps it as the month's only simulation; RemittanceSimulated. The waiting manual SEPA_DD receipts "
            + "with includeInNextRun (R-12-19) the run will remit are in the preview (with their invoiceId and displayNumber) and the KPIs; one "
            + "whose member has left, no longer pays by SEPA_DD, has no account or signed another mandate since is an incident (NO_BANK_ACCOUNT) "
            + "and is not remitted. A month more than three months ahead "
            + "→ 400 VALIDATION_ERROR (S12 §13). Another simulation, run or rollback holding the club's billing lock → 409 BILLING_BUSY." + SERVED,
            responses = @ApiResponse(responseCode = "201", description = "BillingSimulation", useReturnTypeSchema = true))
    public BillingSimulation simulateBilling(@Valid @RequestBody SimulationRequest request) {
        access.tenant();
        access.month("period", request.period());
        return BillingViews.simulation(simulations.simulate(YearMonth.parse(request.period())));
    }

    @PostMapping("/api/v1/billing/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, RUN_EXISTS, SIMULATION_STALE, BILLING_BUSY, IDEMPOTENCY_KEY_REUSED, STALE_VERSION,
            COLLECTION_DATE_TOO_SOON, NO_INVOICES, SEPA_NOT_CONFIGURED})
    @Operation(summary = "createBillingRun", description = ROLES + "R-12-11/12 «2 · GENERA REMESA SEPA (XML)»: one Mongo transaction under the "
            + "club's billing lock (409 BILLING_BUSY) creates the PENDING/COLLECTING invoices numbered {series}-{number:04d} in the members' order "
            + "(last names, first name, member number), one Collection each, the remittance (pain.008 written and validated before the commit), "
            + "advances the included members' nextInvoiceDate, marks the PendingCharges; InvoiceIssued per invoice, InvoiceCollecting (SEPA), "
            + "RemittanceGenerated, BillingRunCreated, one REMITTANCE_GENERATED audit entry with details.invoiceIds. One live run per month (409 "
            + "RUN_EXISTS); the simulation must be the month's, made after the last change of the members, family groups, plans, prices, "
            + "billing.* parameters and the club's configuration, with the same unbilled PendingCharges and waiting includeInNextRun receipts "
            + "(409 SIMULATION_STALE; another club's or an unknown one → 404). Members with an incident are skipped (skipped[]), and so are the "
            + "waiting receipts the simulation listed as incidents (they stay PENDING); the others join the remittance. "
            + "collectionDate (SEPA only) defaults to billing.sepa.collectionDayOfMonth of the billed month (0 = its last day) and must leave two "
            + "business days after today → otherwise 422 COLLECTION_DATE_TOO_SOON {requested, earliest}. Nothing to bill → 422 NO_INVOICES; SEPA "
            + "members without a SEPA writer → 422 SEPA_NOT_CONFIGURED (until E8-T03). The same Idempotency-Key answers the same run." + SERVED,
            responses = @ApiResponse(responseCode = "201", description = "BillingRunResult", useReturnTypeSchema = true))
    public BillingRunResult createBillingRun(@Valid @RequestBody BillingRunRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.month("period", request.period());
        access.simulation(request.simulationId());
        var period = YearMonth.parse(request.period());
        var outcome = runs.locked(() -> transactions.keyed(201, () -> runs.generate(period, request.simulationId(), request.collectionDate()),
                result -> json(BillingViews.runResult(result))));
        return BillingViews.runResult(outcome);
    }

    @GetMapping("/api/v1/billing/runs/{id}")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "billingRun", description = ROLES + "A run with rollbackable and rollbackBlockers computed now (R-12-14)." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "BillingRun", useReturnTypeSchema = true))
    public BillingRun billingRun(@PathVariable String id) {
        access.run(id);
        return BillingViews.run(queries.run(id));
    }

    @PostMapping("/api/v1/billing/runs/{id}/card-charges")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ContractErrors({NOT_FOUND, MODULE_DISABLED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED, PAYMENT_PROVIDER_NOT_ENABLED})
    @Operation(summary = "chargeRunCards", description = ROLES + "R-12-13 [COBRA LES TARGETES]: an off-session PaymentIntent per CARD invoice of the "
            + "run (idempotencyKey = invoiceId, batches of 25), Collection SUBMITTED, invoice COLLECTING; run CHARGING until every Stripe collection "
            + "is resolved by webhook. An invoice without a valid card → FAILED{NO_PAYMENT_METHOD} + N-35, listed in skipped. CARD_CHARGES_STARTED "
            + "audit. A run not GENERATED → 409 INVALID_STATE; STRIPE not enabled → 422 PAYMENT_PROVIDER_NOT_ENABLED. Until E8-T04 there is no "
            + "card provider: every call answers 422 PAYMENT_PROVIDER_NOT_ENABLED after the guards." + SERVED,
            responses = @ApiResponse(responseCode = "202", description = "CardChargesResult", useReturnTypeSchema = true))
    public CardChargesResult chargeRunCards(@PathVariable String id, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.run(id);
        var result = cards.chargeRun(id);
        return new CardChargesResult(result.submitted(), result.skipped().stream().map(skip -> new CardChargeSkip(skip.invoiceId(), skip.reason())).toList());
    }

    @PostMapping("/api/v1/billing/runs/{id}/rollback")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, RUN_NOT_ROLLBACKABLE, BILLING_BUSY, IDEMPOTENCY_KEY_REUSED, STALE_VERSION})
    @Operation(summary = "rollbackBillingRun", description = ROLES + "R-12-14 [Retrocedeix la remesa] with the typed confirmation (exactly "
            + "RETROCEDIR, otherwise 400 VALIDATION_ERROR): one transaction under the billing lock — every invoice of the run rolled back (the "
            + "live ones CANCELLED{ROLLBACK}; one the admin cancelled meanwhile keeps its reason), a new FAILED{ROLLBACK} "
            + "collection each, remittance ROLLED_BACK (file kept), the numbering given back, each member's nextInvoiceDate restored, "
            + "PendingCharge.invoiceId cleared; RemittanceRolledBack, InvoiceCancelled each, one REMITTANCE_ROLLED_BACK audit entry. The month can "
            + "then be simulated and generated again with the same numbers. Not allowed → 409 RUN_NOT_ROLLBACKABLE {reasons[]: REMITTANCE_SUBMITTED · "
            + "COLLECTION_SUBMITTED · INVOICE_PAID · MANUAL_INVOICE_AFTER (an invoice numbered after the run)}; a run already rolled back → 409 "
            + "RUN_NOT_ROLLBACKABLE {reasons: []}." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "RollbackResult", useReturnTypeSchema = true))
    public RollbackResult rollbackBillingRun(@PathVariable String id, @Valid @RequestBody RollbackRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.run(id);
        var outcome = runs.locked(() -> transactions.keyed(200, () -> runs.rollback(id, request.reason(), request.confirmation()),
                result -> json(new RollbackResult(result.cancelledInvoices(), result.restoredMembers()))));
        return new RollbackResult(outcome.cancelledInvoices(), outcome.restoredMembers());
    }

    private byte[] json(Object body) {
        try { return mapper.writeValueAsBytes(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }

    @GetMapping("/api/v1/billing/exports")
    @ContractErrors({VALIDATION_ERROR, MODULE_DISABLED, EXPORT_TOO_LARGE, EXPORT_LIMIT, RATE_LIMITED})
    @Operation(summary = "exportAccounting", description = ROLES + "R-12-26 «Exporta per a comptabilitat» (S14 engine, listKey = accounting): one row "
            + "per invoice line (number, date, month, member number, name, holder's tax id, concept, base, tax %, tax, total, method, status, "
            + "collection date, remittance, mandate or PaymentIntent reference), UTF-8 with BOM, `;`, decimals per the admin's locale. 200 file "
            + "(facturacio-YYYY-MM.csv) or 202 ExportAccepted for a large one; DATA_EXPORTED audit. format defaults to billing.accountingExportFormat." + STUB,
            responses = {
                    @ApiResponse(responseCode = "200", description = "Export file", headers = @Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                            content = {@Content(mediaType = "text/csv", schema = @Schema(type = "string", format = "binary")),
                                    @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", schema = @Schema(type = "string", format = "binary"))}),
                    @ApiResponse(responseCode = "202", description = "Queued export", content = @Content(mediaType = "application/json", schema = @Schema(implementation = ExportAccepted.class)))})
    public org.springframework.http.ResponseEntity<byte[]> exportAccounting(@RequestParam @Schema(pattern = BillingContracts.MONTH) String period,
            @RequestParam(required = false) @Schema(allowableValues = {"csv", "xlsx"}) String format) {
        access.tenant();
        access.month("period", period);
        access.oneOf("format", format, "csv", "xlsx");
        throw new UnsupportedOperationException();
    }
}
