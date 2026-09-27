package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * CATALEG_NOTIFICACIONS audiences (R-11-02): `MEMBER` (the members of the payload), `INSTRUCTORS`, `ADMINS` and
 * `APPLICANT` (an e-mail address without an account: e-mail only). The first three are the rows of a template matrix.
 */
@Schema(enumAsRef = true)
public enum NotificationAudience {
    MEMBER, INSTRUCTORS, ADMINS, APPLICANT;

    /** Whether a `MessageTemplate.matrix` has a row for this audience (S11 §3: `APPLICANT` is fixed by the code). */
    public boolean templated() { return this != APPLICANT; }
}
