package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.payments.api.BillingContracts.*;
import static com.agilityhub.core.payments.api.BillingRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S12 §6 invoices of D6 and D10 (R-12-10, R-12-16…20), ADMIN only, module `BILLING`. An issued invoice is immutable: there is
 * no PATCH (`405 METHOD_NOT_ALLOWED`, T-12-12), only the state actions below, each with the invoice's `version`. Every
 * operation runs its guards and then answers 501 NOT_IMPLEMENTED until E8-T02 (list, detail, manual invoices, payments,
 * failures, cancellations, document) and E8-T04 (retry, refund).
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class InvoicesController {
    static final String ROLES = BillingController.ROLES;
    static final String STUB = BillingController.STUB;
    private final BillingContractAccess access;
    public InvoicesController(BillingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/invoices")
    @ListContract(filterable = {"period", "status", "memberId", "paymentMethodType", "runId", "remittanceId", "issueDate", "total", "kind"},
            sortable = {"number", "issueDate", "total", "memberLastName"},
            columns = {"displayNumber*", "member*", "concept*", "total*", "paymentMethodType*", "status*", "issueDate", "period", "kind", "paidAt", "remittanceId"},
            paged = true, exportable = true,
            fields = {"id", "displayNumber", "number", "issueDate", "period", "member", "concept", "total", "paymentMethodType", "status", "kind", "runId",
                    "remittanceId", "refundedTotal", "paidAt", "failedAt"})
    @ContractErrors({INVALID_FILTER, MODULE_DISABLED})
    @Operation(summary = "invoices", description = ROLES + "D6's receipts, universal list (CONVENCIONS_API §4), newest number first; the chips "
            + "Tots · Pendents · Remesats · Cobrats · Impagats are status filters; q searches the number and the member's name; D10's «Tots els "
            + "rebuts ›» is filter=memberId:eq:{id}. total filters on amountMinor. An undeclared filter, sort or fields key → 400 INVALID_FILTER." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "InvoicePage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = InvoicePage.class))))
    public Object invoices(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.invoiceList(params);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/invoices/{id}")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "invoice", description = ROLES + "D6's drawer: the invoice, its lines, its collections (attempts and refunds) and "
            + "refundedTotal. Another club's → 404 (T-12-21)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Invoice", useReturnTypeSchema = true))
    public Invoice invoice(@PathVariable String id) {
        access.invoice(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, MEMBER_ERASED, IDEMPOTENCY_KEY_REUSED, CURRENCY_MISMATCH})
    @Operation(summary = "createManualInvoice", description = ROLES + "R-12-19: a MANUAL invoice (ADJUSTMENT lines, positive or negative) numbered "
            + "like the others, PENDING; collected by hand (R-12-16) or, for SEPA_DD with includeInNextRun, by the next remittance. "
            + "INVOICE_CREATED_MANUAL audit, InvoiceIssued. A line in another currency → 422 CURRENCY_MISMATCH; another club's member → 404; an erased one → 409 MEMBER_ERASED." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "Invoice", useReturnTypeSchema = true))
    public Invoice createManualInvoice(@Valid @RequestBody ManualInvoiceRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.mutableMember(request.memberId());
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/invoices/{id}/payment")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, STALE_VERSION, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "markInvoicePaid", description = ROLES + "R-12-16 [Marcar cobrat]: a PENDING or FAILED invoice paid by MANUAL or SEPA_DD "
            + "(e.g. a transfer after a return) → Collection MANUAL SUCCEEDED, invoice PAID, InvoicePaid{provider: MANUAL}, INVOICE_MARKED_PAID audit. "
            + "Another state → 409 INVALID_STATE; an old version → 409 STALE_VERSION." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Invoice", useReturnTypeSchema = true))
    public Invoice markInvoicePaid(@PathVariable String id, @Valid @RequestBody InvoicePaymentRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoice(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/invoices/payments")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "markInvoicesPaid", description = ROLES + "R-12-16 [Marcar cobrat (selecció)]: the same as one payment for every invoice, "
            + "all or none (one of them in another state → 409 INVALID_STATE; another club's → 404)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "BulkPaymentResult", useReturnTypeSchema = true))
    public BulkPaymentResult markInvoicesPaid(@Valid @RequestBody BulkPaymentRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoices(request.invoiceIds());
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/invoices/{id}/failure")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, STALE_VERSION, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "markInvoiceFailed", description = ROLES + "R-12-17 «impagat (manual)»: a COLLECTING SEPA invoice, or a PAID one with a SEPA "
            + "collection (a later bank return) → Collection FAILED{BANK_RETURN} (appended), invoice FAILED, InvoiceFailed → N-10, "
            + "INVOICE_MARKED_FAILED audit. No automatic claim or booking block (BR-08). Another state → 409 INVALID_STATE." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Invoice", useReturnTypeSchema = true))
    public Invoice markInvoiceFailed(@PathVariable String id, @Valid @RequestBody InvoiceFailureRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoice(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/invoices/{id}/retry")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, STALE_VERSION, MAX_ATTEMPTS, IDEMPOTENCY_KEY_REUSED, NO_PAYMENT_METHOD})
    @Operation(summary = "retryInvoice", description = ROLES + "R-12-18: a FAILED invoice charged again to the member's card (attempt + 1, "
            + "idempotencyKey invoiceId:attempt) → COLLECTING; the result arrives by webhook. More than billing.stripeMaxAttempts → 409 MAX_ATTEMPTS "
            + "{attempts, max}; no valid card → 422 NO_PAYMENT_METHOD; another state → 409 INVALID_STATE." + STUB,
            responses = @ApiResponse(responseCode = "202", description = "Invoice", useReturnTypeSchema = true))
    public Invoice retryInvoice(@PathVariable String id, @Valid @RequestBody InvoiceRetryRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoice(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/invoices/{id}/refund")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED, REFUND_EXCEEDS_PAID, PAYMENT_PROVIDER_NOT_ENABLED})
    @Operation(summary = "refundInvoice", description = ROLES + "R-12-20: a refund of a SUCCEEDED Stripe collection (amount absent = what is left); "
            + "charge.refunded (webhook) adds it to the collection's refunds and to refundedTotal; PAYMENT_REFUNDED audit. A SEPA or manual "
            + "invoice has no automatic refund (an adjustment invoice instead, T-12-17) → 422 PAYMENT_PROVIDER_NOT_ENABLED; not PAID → 409 "
            + "INVALID_STATE; more than paid → 422 REFUND_EXCEEDS_PAID (rule 0, S12 §6 writes 409)." + STUB,
            responses = @ApiResponse(responseCode = "202", description = "RefundAccepted", useReturnTypeSchema = true))
    public RefundAccepted refundInvoice(@PathVariable String id, @Valid @RequestBody RefundRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoice(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/invoices/{id}/cancellation")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, STALE_VERSION, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "cancelInvoice", description = ROLES + "R-12-19: a PENDING or FAILED invoice → CANCELLED{ADMIN}, InvoiceCancelled, "
            + "INVOICE_CANCELLED audit; never PAID or COLLECTING (409 INVALID_STATE)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Invoice", useReturnTypeSchema = true))
    public Invoice cancelInvoice(@PathVariable String id, @Valid @RequestBody InvoiceCancellationRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoice(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/invoices/{id}/document")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "invoiceDocument", description = ROLES + "The receipt PDF with the club's brand (S14 engine, T-12-20), in the member's "
            + "language with the frozen descriptions. Another club's → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "Receipt PDF",
                    headers = @Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                    content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))))
    public org.springframework.http.ResponseEntity<byte[]> invoiceDocument(@PathVariable String id) {
        access.invoice(id);
        throw new UnsupportedOperationException();
    }
}
