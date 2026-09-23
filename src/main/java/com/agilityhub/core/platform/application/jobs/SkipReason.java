package com.agilityhub.core.platform.application.jobs;

/** S15 §3 `JobRun.skipReason`; NOT_DUE is a tick decision and writes no row. */
public enum SkipReason { DISABLED, MODULE_OFF, CLUB_INACTIVE, MISSED_WINDOW, LOCKED, NOT_DUE }
