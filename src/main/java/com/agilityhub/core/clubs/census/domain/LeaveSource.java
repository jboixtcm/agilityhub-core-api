package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** S13 §3 `LeaveRequest.source` (`MIGRATED`: S18's import, model Annex B and S13 §3 since 27-09). */
@Schema(enumAsRef = true)
public enum LeaveSource { MEMBER, ADMIN, PACK_EXPIRED, MIGRATED }
