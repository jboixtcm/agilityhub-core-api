package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §3 `cancelledBy` of a period or a request. */
@Schema(enumAsRef = true)
public enum LifecycleCanceller { MEMBER, ADMIN, SYSTEM }
