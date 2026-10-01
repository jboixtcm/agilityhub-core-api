package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §3 `InactivityPeriod.history[].source`: who changed the months (R-13-04/05). */
@Schema(enumAsRef = true)
public enum ChangeSource { MEMBER, ADMIN }
