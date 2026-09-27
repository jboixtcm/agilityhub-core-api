package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * CATALEG_NOTIFICACIONS categories (D9): `OPERATIONAL` (e-mail OFF by default for the member), `PERSONAL`, `CLUB_CHANGES`
 * (the only one with SMS for the member), `CLUB_NEWS` and `SYSTEM` (account e-mails: never a template, always e-mail).
 */
@Schema(enumAsRef = true)
public enum NotificationCategory {
    OPERATIONAL, PERSONAL, CLUB_CHANGES, CLUB_NEWS, SYSTEM;

    /** The four categories a `MessageTemplate` and the preference matrix of 12/D10 have (S11 §3, R-11-04). */
    public boolean templated() { return this != SYSTEM; }
}
