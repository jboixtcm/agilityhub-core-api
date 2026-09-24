package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Fictitious process that exercises the framework without any business vertical (E5-T01, S15 WP-15-A).
 * It plans `items` entries and fails the `failAt`-th (1-based, 0 = never); `failPlan` makes the whole run fail.
 * It writes nothing itself, so every write observed during a run belongs to the framework.
 */
@Component
@Profile("test")
public class TestNoopJob implements Job {
    public static final JobDefinition DEFAULT = new JobDefinition(JobName.TEST_NOOP, "test-noop", Cadence.DAILY, "jobs.dailyTime", null, null,
            CatchUpWindow.UNLIMITED, null);
    private volatile JobDefinition definition = DEFAULT;
    private volatile int items;
    private volatile int failAt;
    private volatile boolean failPlan;
    private final AtomicInteger applied = new AtomicInteger();
    private final List<String> appliedBy = java.util.Collections.synchronizedList(new ArrayList<>());
    private final AtomicReference<Runnable> duringApply = new AtomicReference<>();
    private final AtomicReference<Runnable> duringPlan = new AtomicReference<>();

    @Override public JobName name() { return JobName.TEST_NOOP; }
    @Override public JobDefinition definition() { return definition; }

    @Override public List<JobItem> plan(JobContext context) {
        var hook = duringPlan.getAndSet(null);
        if (hook != null) { hook.run(); }
        if (failPlan) { throw new IllegalStateException("Planned failure"); }
        context.parameter("jobs.dailyTime", String.class);
        var plan = new ArrayList<JobItem>();
        for (int i = 1; i <= items; i++) { plan.add(new JobItem("TestItem", "item-" + i, "NOOP", Map.of("position", i))); }
        return plan;
    }
    @Override public JobEffect apply(JobContext context, JobItem item) {
        var hook = duringApply.getAndSet(null);
        if (hook != null) { hook.run(); }
        if (item.entityId().equals("item-" + failAt)) { throw new ApiException(ErrorCode.INVALID_STATE); }
        applied.incrementAndGet();
        appliedBy.add(Thread.currentThread().getName() + "/" + item.entityId());
        return JobEffect.of("NOOP", "applied");
    }

    public void configure(JobDefinition definition, int items, int failAt, boolean failPlan) {
        this.definition = definition; this.items = items; this.failAt = failAt; this.failPlan = failPlan; applied.set(0); appliedBy.clear(); duringApply.set(null);
        duringPlan.set(null);
    }
    /** Runs `hook` once, inside the next `apply` (while the run is RUNNING): lets a test interleave a reaper or another instance. */
    public void duringNextApply(Runnable hook) { duringApply.set(hook); }
    /** Runs `hook` once, inside the next `plan` (also a dry run's): lets a test observe the framework's writes while it plans. */
    public void duringNextPlan(Runnable hook) { duringPlan.set(hook); }
    public void reset() { configure(DEFAULT, 0, 0, false); }
    public int applied() { return applied.get(); }
    /** `thread/entityId` of every applied item, in order: tells which holder applied which item. */
    public List<String> appliedBy() { synchronized (appliedBy) { return List.copyOf(appliedBy); } }
}
