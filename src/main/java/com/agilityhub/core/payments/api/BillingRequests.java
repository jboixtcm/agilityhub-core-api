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
            @Schema(requiredMode = NOT_REQUIRED, description = "Default: billing.sepa.collectionDayOfMonth of the billed month, moved to the next TARGET "
                    + "business day when it is not one (R-12-12, SEPA-05). A date given must be a TARGET business day and leave two TARGET business days "
                    + "after today") LocalDate collectionDate) { }
    public record RollbackRequest(@NotBlank @Size(max = 500) String reason,
            @NotBlank @Schema(description = "The word D6's confirmation dialog asks to type (R-12-14): RETROCEDIR, or FITXER_NO_ENVIAT when the "
                    + "file was never sent to the bank — it lifts only COLLECTION_DATE_PASSED (E156)") String confirmation) { }
    public record SubmissionRequest(@NotNull @Schema(description = "The club-local day the XML was uploaded to the bank (R-12-15)") LocalDate submittedAt) { }
    public record SubmissionUndoRequest(@NotBlank @Size(max = 500) @Schema(description = "SEPA-04 (R-12-15): why the remittance is no longer "
            + "taken as sent, e.g. the bank refused the file or it was marked by mistake; kept in the audit entry") String reason) { }
    public record ManualInvoiceLine(@NotBlank @Size(max = 140) String description, @NotNull @Valid Money base,
            @NotNull @DecimalMin("0") @DecimalMax("100") BigDecimal taxPercent) { }
    public record ManualInvoiceRequest(@NotBlank @Schema(format = "uuid") String memberId, @NotEmpty List<@Valid ManualInvoiceLine> lines,
            @Schema(requiredMode = NOT_REQUIRED, description = "SEPA_DD only: collect it with the next run's remittance (R-12-19)") Boolean includeInNextRun,
            @NotBlank @Size(max = 500) String note,
            @Schema(requiredMode = NOT_REQUIRED, pattern = BillingContracts.MONTH, description = "E11-T15 (R-12-19, R-12-26): the month the receipt is for — "
                    + "D6's month list and the accounting export by period. Absent → the issue month; at most three months either side of it")
            @Pattern(regexp = "[0-9]{4}-(0[1-9]|1[0-2])") String period) { }
    public record InvoicePaymentRequest(@NotNull LocalDate paidAt, @NotNull ManualChannel channel,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 140) String reference, @NotNull Long version) { }
    public record BulkPaymentRequest(@NotEmpty List<@NotBlank String> invoiceIds, @NotNull LocalDate paidAt, @NotNull ManualChannel channel) { }
    public record InvoiceFailureRequest(@NotBlank @Size(max = 500) String reason, @NotNull LocalDate at, @NotNull Long version,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = INCLUDE_RETURNED) Boolean includeInNextRun) {
        public InvoiceFailureRequest(String reason, LocalDate at, Long version) { this(reason, at, version, null); }
    }
    /** SEPA-04 (b), R-12-17/19. */
    static final String INCLUDE_RETURNED = "SEPA-04 (R-12-17, R-12-19): true → the returned periodic receipt waits, FAILED, for the next run's remittance, "
            + "which presents it again with the member's current mandate; absent or false → it waits for no remittance (marked paid or cancelled by hand). "
            + "true on a manual receipt → 400 VALIDATION_ERROR {field: includeInNextRun}";
    public record RemittanceReturnsRequest(@NotEmpty List<@NotBlank String> invoiceIds, @NotBlank @Size(max = 500) String reason,
            @NotNull LocalDate at, @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = INCLUDE_RETURNED) Boolean includeInNextRun) { }
    public record InvoiceRetryRequest(@NotNull Long version) { }
    public record RefundRequest(@Schema(requiredMode = NOT_REQUIRED, description = "Partial amount; absent = what is left to refund") @Valid Money amount,
            @NotBlank @Size(max = 500) String reason) { }
    public record InvoiceCancellationRequest(@NotBlank @Size(max = 500) String reason, @NotNull Long version) { }
    public record UpfrontPaymentRequest(@NotBlank @Schema(format = "uuid") String memberId,
            @Schema(requiredMode = NOT_REQUIRED, format = "uuid") String dogId, @NotNull UpfrontConcept concept, @NotNull @Valid Money amountDue,
            @NotNull @Valid Money amountPaid, @NotNull ManualChannel channel, @NotNull LocalDate paidAt,
            @Schema(requiredMode = NOT_REQUIRED) @Size(max = 140) String reference, @Schema(requiredMode = NOT_REQUIRED) @Size(max = 500) String note) { }
    public record CardSetupRequest(@NotBlank @Schema(format = "uri") String successUrl, @NotBlank @Schema(format = "uri") String cancelUrl) { }
    @Schema(description = "E146 (E11-T15): the admin's correction of a member's next invoice date, with its reason (audited)")
    public record NextInvoiceDateRequest(@NotNull @Schema(description = "Any day from the first of the club's current month twelve months back to the last "
            + "day of the month twelve months ahead") LocalDate nextInvoiceDate, @NotBlank @Size(max = 200) String reason) { }
    public record PackBalanceRequest(@NotBlank @Schema(format = "uuid") String memberId, @NotBlank @Schema(format = "uuid") String dogId,
            @NotBlank @Schema(format = "uuid") String planId, @Schema(requiredMode = NOT_REQUIRED, description = "Default: the plan's pack.sessions") @Positive Integer sessionsTotal,
            @NotNull LocalDate openedOn, @Schema(requiredMode = NOT_REQUIRED, description = "Default: openedOn + validityMonths − 1 day") LocalDate expiresOn,
            @NotBlank @Size(max = 500) String reason) { }
    public record PackAdjustmentRequest(@NotNull Integer delta, @NotBlank @Size(max = 500) String reason,
            @Schema(requiredMode = NOT_REQUIRED, description = "Required to reopen an EXPIRED pack (R-12-24)") LocalDate expiresOn) { }

    /** A Stripe event as Stripe posts it; no field is read before the raw body signature is verified. */
    @Schema(description = "Stripe's event envelope (https://docs.stripe.com/api/events/object); any other key is accepted and ignored")
    public record StripeWebhookEvent(@Schema(example = "evt_123") String id, @Schema(example = "payment_intent.succeeded") String type,
            @Schema(requiredMode = NOT_REQUIRED, description = "Unix seconds") Long created) { }
}
