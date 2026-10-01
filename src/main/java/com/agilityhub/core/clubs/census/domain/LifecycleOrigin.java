package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §3 `origin` of a period or a request: `BACKOFFICE` = created by an admin or under impersonation (R-13-18); `SYSTEM` = `PACK_EXPIRED`. */
@Schema(enumAsRef = true)
public enum LifecycleOrigin { APP, BACKOFFICE, SYSTEM }
