package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.domain.ManualChannel;
import com.agilityhub.core.payments.domain.UpfrontConcept;
import com.agilityhub.core.shared.domain.Money;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S12 §6 request bodies (E8-T01): required by default, the `?` fields of §6 optional. Dates the admin picks (`paidAt`, `at`,
 * `submittedAt`, `collectionDate`, `openedOn`, `expiresOn`) are club-local days (R-12-30); `version` is the invoice's
 * optimistic lock (`409 STALE_VERSION`, R-12-29).
 */
public final class BillingRequests {
    private BillingRequests() { }

    public record SimulationRequest(@NotBlank @Schema(pattern = BillingContracts.MONTH, description = "The month to bill, YYYY-MM") String period) { }
    public record BillingRunRequest(@NotBlank @Schema(pattern = BillingContracts.MONTH) String period, @NotBlank @Schema(format = "uuid") String simulationId,
            @Schema(requiredMode = NOT_REQUIRED, description = "Default: billing.sepa.collectionDayOfMonth of the billed month (R-12-12)") LocalDate collectionDate) { }
    public record RollbackRequest(@NotBlank @Size(max = 500) String reason,
            @NotBlank @Schema(description = "The word D6's confirmation dialog asks to type (R-12-14)") String confirmation) { }
    public record SubmissionRequest(@NotNull @Schema(description = "The club-local day the XML was uploaded to the bank (R-12-15)") LocalDate submittedAt) { }
    public record ManualInvoiceLine(@NotBlank @Size(max = 140) String description, @NotNull @Valid Money base,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxPercent) { }
    public record ManualInvoiceRequest(@NotBlank @Schema(format = "uuid") String memberId, @NotEmpty List<@Valid ManualInvoiceLine> lines,
            @Schema(requiredMode = NOT_REQUIRED, description = "SEPA_DD only: collect it with the next run's remittance (R-12-19)") Boolean includeInNextRun,
            @NotBlank @Size(max = 500) String note) { }
    public record InvoicePaymentRequest(@NotNull LocalDate paidAt, @NotNull ManualChannel channel,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 140) String reference, @NotNull Long version) { }
    public record BulkPaymentRequest(@NotEmpty List<@NotBlank String> invoiceIds, @NotNull LocalDate paidAt, @NotNull ManualChannel channel) { }
    public record InvoiceFailureRequest(@NotBlank @Size(max = 500) String reason, @NotNull LocalDate at, @NotNull Long version) { }
    public record InvoiceRetryRequest(@NotNull Long version) { }
    public record RefundRequest(@Schema(requiredMode = NOT_REQUIRED, description = "Partial amount; absent = what is left to refund") @Valid Money amount,
            @NotBlank @Size(max = 500) String reason) { }
    public record InvoiceCancellationRequest(@NotBlank @Size(max = 500) String reason, @NotNull Long version) { }
    public record UpfrontPaymentRequest(@NotBlank @Schema(format = "uuid") String memberId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String dogId, @NotNull UpfrontConcept concept, @NotNull @Valid Money amountDue,
            @NotNull @Valid Money amountPaid, @NotNull ManualChannel channel, @NotNull LocalDate paidAt,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 140) String reference, @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String note) { }
    public record CardSetupRequest(@NotBlank @Schema(format = "uri") String successUrl, @NotBlank @Schema(format = "uri") String cancelUrl) { }
    public record PackBalanceRequest(@NotBlank @Schema(format = "uuid") String memberId, @NotBlank @Schema(format = "uuid") String dogId,
            @NotBlank @Schema(format = "uuid") String planId, @Schema(requiredMode = NOT_REQUIRED, description = "Default: the plan's pack.sessions") @Positive Integer sessionsTotal,
            @NotNull LocalDate openedOn, @Schema(requiredMode = NOT_REQUIRED, description = "Default: openedOn + validityMonths − 1 day") LocalDate expiresOn,
            @NotBlank @Size(max = 500) String reason) { }
    public record PackAdjustmentRequest(@NotNull Integer delta, @NotBlank @Size(max = 500) String reason,
            @Schema(requiredMode = NOT_REQUIRED, description = "Required to reopen an EXPIRED pack (R-12-24)") LocalDate expiresOn) { }

    /** A Stripe event as Stripe posts it; only `id` and `type` are read before the signature is verified. */
    @Schema(description = "Stripe's event envelope (https://docs.stripe.com/api/events/object); any other key is accepted and ignored")
    public record StripeWebhookEvent(@Schema(example = "evt_123") String id, @Schema(example = "payment_intent.succeeded") String type,
            @Schema(requiredMode = NOT_REQUIRED, description = "Unix seconds") Long created) { }
}
