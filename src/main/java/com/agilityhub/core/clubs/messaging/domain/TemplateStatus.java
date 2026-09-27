package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** Template state machine (S11 §5): `ACTIVE` ⇄ `DISABLED` (not mandatory ones), `ARCHIVED` (only `CUSTOM`, nothing is deleted). */
@Schema(enumAsRef = true)
public enum TemplateStatus {
    ACTIVE, DISABLED, ARCHIVED
}
