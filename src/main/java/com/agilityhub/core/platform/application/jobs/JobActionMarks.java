package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.persistence.jobs.*;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import org.springframework.stereotype.Service;

/** R-15-04: a partial run cannot resend an already committed reminder when its remaining items are retried. */
@Service
public class JobActionMarks {
    private final JobActionMarkRepository marks;
    private final Clock clock;
    public JobActionMarks(JobActionMarkRepository marks, Clock clock) { this.marks = marks; this.clock = clock; }
    private String id(String action, String period) { return TenantContext.require() + ":" + action + ":" + period; }
    public boolean contains(String action, String period) { return marks.findById(id(action, period)).isPresent(); }
    /** Caller holds the job lock and a Mongo transaction; the mark and event either both commit or both roll back. */
    public void record(String action, String period) {
        marks.insert(new JobActionMark(id(action, period), TenantContext.require(), action, period, clock.instant()));
    }
}
