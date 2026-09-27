package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** The system icon set of a template (S11 §3): the same ids as the mockups (`#i-…`), never emojis. */
@Schema(enumAsRef = true)
public enum TemplateIcon {
    check, x, unlock, warn, up, heart, bell, doc, flag, mail, cal, clock, info, paw, cone, lock
}
