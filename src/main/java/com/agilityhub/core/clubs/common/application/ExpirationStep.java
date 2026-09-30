package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobEffect;
import com.agilityhub.core.platform.application.jobs.JobItem;
import java.util.List;

/**
 * One step of S15 R-15-15 P5 `expirations` ({@link ExpirationsJob}). Each step is independent, with its own module and its own
 * counter: when its module is off, P5 skips it and runs the others (R-15-03). A step reaches the context it expires through a
 * port of this context, never that context's services directly.
 */
public interface ExpirationStep {
    /** The step's letter in R-15-15 (`a`…`h`): the order in which a run plans the steps. */
    char letter();
    /** The module the step needs; null when it needs none. */
    Module module();
    /** The `entityType` of this step's items, one per step: {@link ExpirationsJob#apply} gives an item back to its step by it. */
    String entityType();
    /** Only reads, as {@link com.agilityhub.core.platform.application.jobs.Job#plan}. */
    List<JobItem> plan(JobContext context);
    /** One item, in its own transaction, as {@link com.agilityhub.core.platform.application.jobs.Job#apply}. */
    JobEffect apply(JobContext context, JobItem item);
}
