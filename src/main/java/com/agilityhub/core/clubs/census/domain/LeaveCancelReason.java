package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §3 `LeaveRequest.cancelReason`. */
@Schema(enumAsRef = true)
public enum LeaveCancelReason { WITHDRAWN, ADMIN, PACK_RENEWED }
