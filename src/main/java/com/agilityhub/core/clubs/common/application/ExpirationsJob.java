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
 * E5-T31 (ruling E80): step h ({@link SignupCheckoutExpiryStep}, `BILLING`) is the first step with code. Steps a–g arrive with
 * their verticals (S15 §9: E8, E9).
 */
@Component
public class ExpirationsJob implements Job {
    static final JobEffect NOT_IN_SCOPE = new JobEffect("NOT_IN_SCOPE", Map.of(), Map.of());
    private final List<ExpirationStep> steps;
    public ExpirationsJob(List<ExpirationStep> steps) {
        var types = new HashSet<String>();
        for (var step : steps) {
            if (!types.add(step.entityType())) { throw new IllegalStateException("Two P5 steps plan " + step.entityType() + " items"); }
        }
        this.steps = steps.stream().sorted(Comparator.comparing(ExpirationStep::letter)).toList();
    }
    @Override public JobName name() { return JobName.EXPIRATIONS; }

    @Override public List<JobItem> plan(JobContext context) {
        var items = new ArrayList<JobItem>();
        for (var step : steps) { if (on(step, context)) { items.addAll(step.plan(context)); } }
        return List.copyOf(items);
    }

    @Override public JobEffect apply(JobContext context, JobItem item) {
        return steps.stream().filter(step -> step.entityType().equals(item.entityType()) && on(step, context)).findFirst()
                .map(step -> step.apply(context, item)).orElse(NOT_IN_SCOPE);
    }

    private static boolean on(ExpirationStep step, JobContext context) {
        return step.module() == null || context.config().modules().contains(step.module());
    }
}
