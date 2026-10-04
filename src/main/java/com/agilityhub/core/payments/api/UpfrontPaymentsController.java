package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.domain.UpfrontStatus;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.payments.api.BillingContracts.*;
import static com.agilityhub.core.payments.api.BillingRequests.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S12 §6 payments on the spot of D10 «Pagaments a l'acte» (R-12-20, R-12-23), ADMIN only, module `BILLING`. Every operation
 * runs its guards and then answers 501 NOT_IMPLEMENTED until E8-T02 (record) and E8-T04 (refund).
 */
@RestController
@RequiresModule(Module.BILLING)
@PreAuthorize(BillingController.ADMIN)
public class UpfrontPaymentsController {
    static final String ROLES = BillingController.ROLES;
    static final String STUB = BillingController.STUB;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.payments.application.PaymentRefunds refunds;
    @org.springframework.beans.factory.annotation.Autowired private com.agilityhub.core.payments.application.BillingTransactions transactions;
    @org.springframework.beans.factory.annotation.Autowired private com.fasterxml.jackson.databind.ObjectMapper mapper;
    private final BillingContractAccess access;
    public UpfrontPaymentsController(BillingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/upfront-payments")
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED})
    @Operation(summary = "upfrontPayments", description = ROLES + "D10 «Pagaments a l'acte»: a member's payments on the spot (entry fee, first "
            + "month, packs, single classes, activities), newest first, optionally of one status. Another club's member → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "UpfrontPayments", useReturnTypeSchema = true))
    public UpfrontPayments upfrontPayments(@RequestParam @Schema(format = "uuid") String memberId, @RequestParam(required = false) UpfrontStatus status) {
        access.member(memberId);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/upfront-payments")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, MEMBER_ERASED, IDEMPOTENCY_KEY_REUSED, AMOUNT_EXCEEDS_DUE, PLAN_NOT_PACK})
    @Operation(summary = "recordUpfrontPayment", description = ROLES + "R-12-23 [Registra un pagament]: a MANUAL payment (cash, transfer, Bizum) "
            + "with amountPaid ≤ amountDue (PARTIAL when less; more → 422 AMOUNT_EXCEEDS_DUE). A PACK payment opens the dog's pack (PackOpened; "
            + "a plan that is not a pack → 422 PLAN_NOT_PACK; one pack per payment). UpfrontPaymentRecorded, UPFRONT_PAYMENT_RECORDED audit. "
            + "Another club's member or dog → 404; an erased member → 409 MEMBER_ERASED." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "UpfrontPayment", useReturnTypeSchema = true))
    public UpfrontPayment recordUpfrontPayment(@Valid @RequestBody UpfrontPaymentRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.mutableMember(request.memberId());
        if (request.dogId() != null) { access.memberDog(request.memberId(), request.dogId()); }
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/upfront-payments/{id}/refund")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @ContractErrors({VALIDATION_ERROR, NOT_FOUND, MODULE_DISABLED, INVALID_STATE, IDEMPOTENCY_KEY_REUSED, REFUND_EXCEEDS_PAID, CURRENCY_MISMATCH, PAYMENT_PROVIDER_NOT_ENABLED, PROVIDER_CONFIG_INVALID, RATE_LIMITED})
    @Operation(summary = "refundUpfrontPayment", description = ROLES + "R-12-20: the refund of a payment collected by Stripe Checkout (amount absent "
            + "= what is left); charge.refunded adds it to refunds and a full one makes it REFUNDED; PAYMENT_REFUNDED audit. A MANUAL one has no "
            + "automatic refund → 422 PAYMENT_PROVIDER_NOT_ENABLED; not PAID → 409 INVALID_STATE; more than paid → 422 REFUND_EXCEEDS_PAID." + BillingController.SERVED,
            responses = @ApiResponse(responseCode = "202", description = "RefundAccepted", useReturnTypeSchema = true))
    public RefundAccepted refundUpfrontPayment(@PathVariable String id, @Valid @RequestBody RefundRequest request,
            @RequestHeader("Idempotency-Key") @Schema(format = "uuid") UUID idempotencyKey) {
        access.upfrontPayment(id);
        var result = refunds.upfront(id, request.amount(), request.reason(), "upfront-refund:" + id + ":" + idempotencyKey);
        refunds.execute(result.id());
        return transactions.keyed(202, () -> new RefundAccepted(result.id(), result.amount(), null), value -> {
            try { return mapper.writeValueAsBytes(value); } catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
        });
    }
}
