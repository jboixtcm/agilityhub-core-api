package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §3 `InactivityPeriod.finishReason` (R-13-05, R-13-10, R-13-13). */
@Schema(enumAsRef = true)
public enum InactivityFinishReason { SCHEDULED, ADMIN, LEAVE }
