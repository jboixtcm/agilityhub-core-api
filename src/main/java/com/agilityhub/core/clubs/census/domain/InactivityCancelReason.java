package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §3 `InactivityPeriod.cancelReason`. */
@Schema(enumAsRef = true)
public enum InactivityCancelReason { WITHDRAWN, EXPIRED, LEAVE }
