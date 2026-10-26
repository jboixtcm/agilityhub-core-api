package com.agilityhub.core.migration.domain;

import java.util.Objects;

/** Explicit staged execution is distinct from the compatible, atomic full-import path. */
public record MigrationExecutionRequest(MigrationSteps steps, boolean staged, String resumeRunId, ReapplyApproval approval) {
    public MigrationExecutionRequest {
        Objects.requireNonNull(steps);
        if (resumeRunId!=null && (!staged || resumeRunId.isBlank() || approval!=null)) {
            throw new IllegalArgumentException("--resume-run requires staged execution and cannot be combined with --allow-reapply");
        }
    }
    public static MigrationExecutionRequest full() { return new MigrationExecutionRequest(MigrationSteps.all(),false,null,null); }
}
