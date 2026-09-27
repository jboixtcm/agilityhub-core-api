package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * The native actions of a notification (R-11-11), fixed by the code and never by the template (R28-08): the eight of the
 * catalog's header plus `OPEN_JOBS`, `OPEN_EXPORT`, `OPEN_CHALLENGE`, `OPEN_MEMBER` and `OPEN_SIGNUP` of its Annex A.
 */
@Schema(enumAsRef = true)
public enum NotificationActionType {
    CHANGE_CLASS, CLAIM_SEAT, OPEN_BOOKING, OPEN_DOG, OPEN_TASKS, OPEN_INVOICES, OPEN_ACTIVITY, OPEN_SETUP, OPEN_SIGNUP, OPEN_MEMBER,
    OPEN_JOBS, OPEN_EXPORT, OPEN_CHALLENGE
}
