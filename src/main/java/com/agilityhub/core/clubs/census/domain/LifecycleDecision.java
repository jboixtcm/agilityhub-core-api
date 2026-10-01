package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 R-13-05/R-13-10: the admin's decision on a request. */
@Schema(enumAsRef = true)
public enum LifecycleDecision { APPROVED, DENIED }
