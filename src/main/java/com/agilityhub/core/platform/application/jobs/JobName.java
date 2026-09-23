package com.agilityhub.core.platform.application.jobs;

/** S15 R-15-01 processes in the fixed catalog order of the tick. */
public enum JobName {
    WEEK_OPENING, RISK_REVIEW, NO_SHOW_NOTICES, REMINDERS, EXPIRATIONS,
    WAITLIST_FIFO, PAYMENT_TIMEOUTS, CLASS_FINISHING, CLEANUP, BILLING_REMINDER,
    /** Fictitious framework job (E5-T01): its bean exists only under the `test` profile and it is never listed. */
    TEST_NOOP
}
