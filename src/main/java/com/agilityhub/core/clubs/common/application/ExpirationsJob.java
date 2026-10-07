package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.platform.application.jobs.*;
import java.util.*;
import org.springframework.stereotype.Component;

/**
 * S15 R-15-15 P5 `expirations` (daily at `jobs.dailyTime`, unlimited catch-up, no module of its own): the run plans every
 * {@link ExpirationStep} whose module is on, in the order of their letters, and gives each item back to the step that planned
 * it. A step whose module is off is skipped inside P5, and the other steps still run (R-15-03). Each step keeps its own
 * idempotency mark (R-15-04), so two runs give one effect.
 * <p>
 * Each action belongs to exactly one step. The SMS counter needs no reset (S11, ruling E81).
 */
@Component
public class ExpirationsJob implements Job {
    static final JobEffect NOT_IN_SCOPE = new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of());
    private final List<ExpirationStep> steps;
    public ExpirationsJob(List<ExpirationStep> steps) {
        var actions = new HashSet<String>();
        for (var step : steps) {
            for (var action : step.actions()) {
                if (!actions.add(action)) { throw new IllegalStateException("Two P5 steps plan " + action + " items"); }
            }
        }
        this.steps = steps.stream().sorted(Comparator.comparing(ExpirationStep::letter)).toList();
    }
    @Override public JobName name() { return JobName.EXPIRATIONS; }

    @Override public List<JobItem> plan(JobContext context) {
        var items = new ArrayList<JobItem>();
        for (var step : steps) {
            step.counters().forEach(counter -> context.recorder().count(counter, 0));
            if (on(step, context)) { items.addAll(step.plan(context)); }
        }
        return List.copyOf(items);
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        return steps.stream().filter(step -> step.actions().contains(item.action()) && on(step, context)).findFirst()
                .map(step -> step.apply(context, item)).orElse(NOT_IN_SCOPE);
    }

    private static boolean on(ExpirationStep step, JobContext context) {
        return step.module() == null || context.config().modules().contains(step.module());
    }
}
