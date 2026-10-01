package com.agilityhub.core.clubs.census.domain;

import io.swagger.v3.oas.annotations.media.Schema;

/** `Member.leftReason` (S13 §3, model Annex B): S04's rejection, the three S13 sources and S18's import. */
@Schema(enumAsRef = true)
public enum MemberLeftReason { SIGNUP_REJECTED, LEAVE_REQUEST, ADMIN, PACK_EXPIRED, MIGRATED }
