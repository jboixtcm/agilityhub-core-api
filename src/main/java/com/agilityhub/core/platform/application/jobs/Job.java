package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.application.ClubConfig;
import java.util.List;

/**
 * S15 R-15-08 process contract. `plan` only reads (same parameters, same Clock) and returns the scope;
 * `apply` changes one item inside its own Mongo transaction together with the outbox (R-15-10).
 * Owning contexts register implementations as beans; the platform never imports them.
 */
public interface Job {
    JobName name();
    List<JobItem> plan(JobContext context);
    JobEffect apply(JobContext context, JobItem item);
    /** The R-15-01 row; only the fictitious test job overrides it. */
    default JobDefinition definition() { return JobCatalog.definition(name()); }
    /** Extra guard beyond the module (for example `waitlist.mode = FIFO`); false records SKIPPED{MODULE_OFF}. */
    default boolean activeFor(ClubConfig config) { return true; }
    /**
     * A real run of this process ended FAILED and its row is closed: by its own holder (a job-level exception or a lost
     * lease) or by the reaper after its process died. Lets a process give back what its plan claimed (E5-T13: the P9
     * platform cycle). Called inside the run's club scope and outside any transaction; a failure here is only logged.
     */
    default void failed(String clubId, String runId) { }
}
