package com.agilityhub.core.clubs.messaging.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** `CATALOG` templates come from the seed of a catalog code and are never deleted; `CUSTOM` ones come from [＋ Nova plantilla]. */
@Schema(enumAsRef = true)
public enum TemplateKind {
    CATALOG, CUSTOM
}
