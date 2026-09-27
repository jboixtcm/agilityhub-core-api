package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** CATALEG_NOTIFICACIONS channels: `APP` (feed 11), `EMAIL`, `SMS` (every phone of the member) and `PUSH` (web push). */
@Schema(enumAsRef = true)
public enum NotificationChannel {
    APP, EMAIL, SMS, PUSH;

    /** The columns of a `MessageTemplate.matrix` (S11 §3): `PUSH` is fixed by the code, never stored in a template. */
    public boolean templated() { return this != PUSH; }
}
