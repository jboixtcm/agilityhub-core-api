package com.agilityhub.core.shared.application.contract;

/** S04 D2 / S14 D1 presentation warnings, shared by the two response contracts. */
@io.swagger.v3.oas.annotations.media.Schema(enumAsRef = true)
public enum SignupWarning {
    NO_IMAGE_CONSENT, ACCOUNT_NOT_PROVIDED, DOCUMENT_PENDING,
    FAMILY_HOLDER_NOT_FOUND, UPFRONT_UNPAID, READMISSION
}
