package com.agilityhub.core.platform.application.jobs;

/** S15 §5; DUE is transient and never stored. */
public enum JobStatus { RUNNING, SUCCEEDED, PARTIAL, FAILED, SKIPPED }
