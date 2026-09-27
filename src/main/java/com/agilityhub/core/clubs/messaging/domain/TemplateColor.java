package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** Template colour (S11 §3), a theme token: `NEUTRAL` text2 · `OK` ok · `WARNING` avis · `ERROR` error · `ACCENT` taronja. */
@Schema(enumAsRef = true)
public enum TemplateColor {
    NEUTRAL, OK, WARNING, ERROR, ACCENT
}
