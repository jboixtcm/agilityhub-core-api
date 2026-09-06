package com.agilityhub.core.platform.application.audit;

/** Implemented subset of the closed action list in S14 R-14-09. */
public enum AuditAction {
    IMPERSONATION_STARTED,
    ACCOUNT_EMAIL_STATUS_CHANGED,
    PARAMETER_CHANGED,
    CLUB_UPDATED,
    CLUB_MODULES_CHANGED,
    CLUB_STATUS_CHANGED
}
