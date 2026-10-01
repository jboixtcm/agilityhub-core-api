package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §5 `InactivityPeriod.state`. */
@Schema(enumAsRef = true)
public enum InactivityState { REQUESTED, APPROVED, ACTIVE, FINISHED, DENIED, CANCELLED }
