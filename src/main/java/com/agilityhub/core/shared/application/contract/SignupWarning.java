package com.agilityhub.core.shared.application.contract;

/** S04 D2 / S14 D1 presentation warnings, shared by the two response contracts. */
@io.swagger.v3.oas.annotations.media.Schema(enumAsRef = true)
public enum SignupWarning {
    CARD_INVALID, NO_IMAGE_CONSENT, ACCOUNT_NOT_PROVIDED, DOCUMENT_PENDING,
    FAMILY_HOLDER_NOT_FOUND, UPFRONT_UNPAID, READMISSION,
    /**
     * D2 `dryRun` only (S04 §5, E39; R-04-16, INC-39): a checkout of the submission is in progress, so a plan change or an
     * amount collected by hand would be refused.
     */
    CHECKOUT_PENDING,
    /** D2 `dryRun` and validation (S04 §5, E39b): the plan change leaves more paid than the new quote; the club refunds it (S12). */
    PAID_EXCEEDS_QUOTE
}
