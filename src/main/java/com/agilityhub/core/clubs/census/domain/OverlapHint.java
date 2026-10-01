package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 R-13-02 `INACTIVITY_OVERLAP.details.hint`: modify the live period instead of requesting a new one. */
@Schema(enumAsRef = true)
public enum OverlapHint { EXTEND }
