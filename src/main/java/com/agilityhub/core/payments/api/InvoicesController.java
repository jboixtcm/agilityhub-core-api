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
 * no PATCH (`405 METHOD_NOT_ALLOWED`, T-12-12), only the state actions below, each with the invoice's `version` where §6 takes
 * one, each its own transaction keyed by its `Idempotency-Key` (a repeat answers the stored response). E8-T02 serves the list,
 * the detail, the manual invoices, payments, failures, cancellations and the receipt PDF; retry and refund answer 501 until
 * E8-T04.
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class InvoicesController {
    static final String ROLES = BillingController.ROLES;
    static final String STUB = BillingController.STUB;
    static final String SERVED = BillingController.SERVED;
    private final BillingContractAccess access; private final com.agilityhub.core.shared.application.lists.ListEngine lists;
    private final com.agilityhub.core.payments.application.InvoiceActions actions; private final com.agilityhub.core.payments.application.BillingTransactions transactions;
    private final com.agilityhub.core.payments.application.ReceiptPdf receipts; private final com.agilityhub.core.platform.application.ClubConfigService configs;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public InvoicesController(BillingContractAccess access, com.agilityhub.core.shared.application.lists.ListEngine lists,
            com.agilityhub.core.payments.application.InvoiceActions actions, com.agilityhub.core.payments.application.BillingTransactions transactions,
            com.agilityhub.core.payments.application.ReceiptPdf receipts, com.agilityhub.core.platform.application.ClubConfigService configs,
            com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.access = access; this.lists = lists; this.actions = actions; this.transactions = transactions; this.receipts = receipts;
        this.configs = configs; this.mapper = mapper;
    }

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
            + "rebuts ›» is filter=memberId:eq:{id}. total filters on amountMinor. concept is the first line's frozen description, «(+n)» when "
            + "there are more lines. An undeclared filter, sort or fields key → 400 INVALID_FILTER." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "InvoicePage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = InvoicePage.class))))
    public Object invoices(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.invoiceList(params);
        return lists.list("invoices", params);
    }

    @GetMapping("/api/v1/invoices/{id}")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "invoice", description = ROLES + "D6's drawer: the invoice, its lines, its collections (attempts and refunds, oldest first) "
            + "and refundedTotal. A collection's providerRef is Stripe's PaymentIntent, «{mandateRef}/{endToEndId}» for SEPA, the channel (and "
            + "reference) of a manual payment." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "Invoice", useReturnTypeSchema = true))
    public Invoice invoice(@PathVariable String id) {
        access.invoice(id);
        var detail = actions.detail(id);
        return BillingViews.invoice(detail.invoice(), detail.collections());
    }

    @PostMapping("/api/v1/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, MEMBER_ERASED, BILLING_BUSY, IDEMPOTENCY_KEY_REUSED, STALE_VERSION, CURRENCY_MISMATCH})
    @Operation(summary = "createManualInvoice", description = ROLES + "R-12-19: a MANUAL invoice (ADJUSTMENT lines, positive or negative, base + "
            + "round_half_even(base × taxPercent / 100)) numbered like the others from the series counter, PENDING, with the member's payment method "
            + "frozen; collected by hand (R-12-16) or, for SEPA_DD with includeInNextRun, by a later remittance. INVOICE_CREATED_MANUAL audit, "
            + "InvoiceIssued. While a run holds the club's billing lock → 409 BILLING_BUSY. A line in another currency → 422 CURRENCY_MISMATCH; "
            + "another club's member → 404; an erased one → 409 MEMBER_ERASED." + SERVED,
            responses = @ApiResponse(responseCode = "201", description = "Invoice", useReturnTypeSchema = true))
    public Invoice createManualInvoice(@Valid @RequestBody ManualInvoiceRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.mutableMember(request.memberId());
        var lines = request.lines().stream().map(line -> new com.agilityhub.core.payments.application.InvoiceActions.ManualLine(line.description(), line.base(),
                line.taxPercent())).toList();
        return keyed(201, () -> actions.createManual(request.memberId(), lines, Boolean.TRUE.equals(request.includeInNextRun()), request.note()));
    }

    @PostMapping("/api/v1/invoices/{id}/payment")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, STALE_VERSION, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "markInvoicePaid", description = ROLES + "R-12-16 [Marcar cobrat]: a PENDING or FAILED invoice paid by MANUAL or SEPA_DD "
            + "(e.g. a transfer after a return) → Collection MANUAL SUCCEEDED (channel, reference), invoice PAID, InvoicePaid{provider: MANUAL}, "
            + "INVOICE_MARKED_PAID audit. paidAt is a club-local day, not after today (400 VALIDATION_ERROR). Another state or method → 409 "
            + "INVALID_STATE {status}; an old version → 409 STALE_VERSION." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "Invoice", useReturnTypeSchema = true))
    public Invoice markInvoicePaid(@PathVariable String id, @Valid @RequestBody InvoicePaymentRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoice(id);
        return keyed(200, () -> actions.markPaid(id, request.paidAt(), request.channel(), request.reference(), request.version()));
    }

    @PostMapping("/api/v1/invoices/payments")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED, STALE_VERSION})
    @Operation(summary = "markInvoicesPaid", description = ROLES + "R-12-16 [Marcar cobrat (selecció)]: the same as one payment for every invoice, "
            + "in one transaction, all or none (one of them in another state → 409 INVALID_STATE; another club's → 404)." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "BulkPaymentResult", useReturnTypeSchema = true))
    public BulkPaymentResult markInvoicesPaid(@Valid @RequestBody BulkPaymentRequest request, @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoices(request.invoiceIds());
        var paid = transactions.keyed(200, () -> actions.markPaid(request.invoiceIds(), request.paidAt(), request.channel()), result -> json(bulk(result)));
        return bulk(paid);
    }

    @PostMapping("/api/v1/invoices/{id}/failure")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, STALE_VERSION, IDEMPOTENCY_KEY_REUSED})
    @Operation(summary = "markInvoiceFailed", description = ROLES + "R-12-17 «impagat (manual)»: a COLLECTING SEPA invoice, or a PAID one with a SEPA "
            + "collection (a later bank return) → Collection FAILED{BANK_RETURN} (appended), invoice FAILED with the reason, InvoiceFailed → N-10 to "
            + "the admins, INVOICE_MARKED_FAILED audit. No automatic claim or booking block (BR-08). at is a club-local day, not after today. "
            + "Another state → 409 INVALID_STATE {status}." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "Invoice", useReturnTypeSchema = true))
    public Invoice markInvoiceFailed(@PathVariable String id, @Valid @RequestBody InvoiceFailureRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoice(id);
        return keyed(200, () -> actions.markFailed(id, request.reason(), request.at(), request.version()));
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
    @Operation(summary = "cancelInvoice", description = ROLES + "R-12-19: a PENDING or FAILED invoice → CANCELLED with the admin's reason, "
            + "InvoiceCancelled{reason: ADMIN}, INVOICE_CANCELLED audit; never PAID or COLLECTING (409 INVALID_STATE {status})." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "Invoice", useReturnTypeSchema = true))
    public Invoice cancelInvoice(@PathVariable String id, @Valid @RequestBody InvoiceCancellationRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.invoice(id);
        return keyed(200, () -> actions.cancel(id, request.reason(), request.version()));
    }

    @GetMapping("/api/v1/invoices/{id}/document")
    @ContractErrors({NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "invoiceDocument", description = ROLES + "The receipt PDF («Rebut») with the club's mark (T-12-20), rendered synchronously "
            + "in the member's language (their signup language, else the club's) with the descriptions frozen at issue; the payment method masked, "
            + "never an IBAN. Content-Disposition attachment «{displayNumber}.pdf»." + SERVED,
            responses = @ApiResponse(responseCode = "200", description = "Receipt PDF",
                    headers = @Header(name = "Content-Disposition", schema = @Schema(type = "string")),
                    content = @Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary"))))
    public org.springframework.http.ResponseEntity<byte[]> invoiceDocument(@PathVariable String id) {
        access.invoice(id);
        var invoice = actions.detail(id).invoice();
        var config = configs.get(com.agilityhub.core.shared.application.TenantContext.require());
        String language = actions.memberLocale(invoice.memberId()).orElse(config.club().defaultLocale());
        return pdf(invoice.displayNumber(), receipts.render(invoice, false, config, java.util.Locale.forLanguageTag(language)));
    }

    static org.springframework.http.ResponseEntity<byte[]> pdf(String displayNumber, byte[] body) {
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, org.springframework.http.ContentDisposition.attachment()
                        .filename(displayNumber + ".pdf").build().toString()).body(body);
    }
    private Invoice keyed(int status, java.util.function.Supplier<com.agilityhub.core.payments.application.InvoiceActions.InvoiceDetail> work) {
        var detail = transactions.keyed(status, work, result -> json(BillingViews.invoice(result.invoice(), result.collections())));
        return BillingViews.invoice(detail.invoice(), detail.collections());
    }
    private static BulkPaymentResult bulk(java.util.List<com.agilityhub.core.payments.application.InvoiceActions.InvoiceDetail> paid) {
        return new BulkPaymentResult(paid.size(), paid.stream().map(detail -> BillingViews.invoice(detail.invoice(), detail.collections())).toList());
    }
    private byte[] json(Object body) {
        try { return mapper.writeValueAsBytes(body); }
        catch (com.fasterxml.jackson.core.JsonProcessingException failure) { throw new IllegalStateException(failure); }
    }
}
