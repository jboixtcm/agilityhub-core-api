package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §5 `LeaveRequest.state`. */
@Schema(enumAsRef = true)
public enum LeaveRequestState { PENDING, APPROVED, DENIED, CANCELLED }
