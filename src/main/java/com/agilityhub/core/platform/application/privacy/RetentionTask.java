package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.jobs.JobContext;
import java.util.List;

/**
 * One part of S14 R-14-16 that another context owns, run by the monthly {@link RetentionSweepJob} in the current club: (c) the
 * remittance XML files of `payments`, (e) the expired exports of `clubs.common`, and (E11-T16, S15 R-15-19) the outbox records
 * and run traces that reached an erased member after its erasure ({@link PrivacyTraces}; round 2: its instructor profile's
 * `InstructorChanged`, `clubs.catalogs`' {@code InstructorTraces}). `due` only reads; `apply` changes one
 * item and is idempotent (false = nothing left to do).
 */
public interface RetentionTask {
    String entityType();
    String action();
    /** The JobRun counter (no dots, S15 R-15-10). */
    String counter();
    List<String> due(JobContext context);
    boolean apply(JobContext context, String entityId);
}
