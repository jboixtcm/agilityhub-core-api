package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.census.application.CensusExpirations;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import java.util.*;
import java.util.function.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** P5's adapter reaches the owning context only through its application API. */
@Configuration(proxyBeanMethods = false)
public class CensusExpirationSteps {
    @Bean ExpirationStep inactivityExpirationStep(CensusExpirations census) {
        return new Step('b', Module.INACTIVITY, Set.of("START_INACTIVITY", "END_INACTIVITY", "CANCEL_STALE_INACTIVITY"),
                Set.of("startedInactivity", "endedInactivity", "cancelledInactivity"), census::inactivity, census::apply);
    }
    @Bean ExpirationStep leaveExpirationStep(CensusExpirations census) {
        return new Step('c', null, Set.of("LEAVE"), Set.of("leftMembers"), census::leaves, census::apply);
    }
    @Bean ExpirationStep signupAgingStep(CensusExpirations census) {
        return new Step('d', null, Set.of("REMIND_SIGNUPS"), Set.of("signupReminders", "alreadyDone"), census::signups, census::apply);
    }
    @Bean ExpirationStep documentReminderStep(CensusExpirations census) {
        return new Step('e', null, Set.of("REMIND_DOCUMENT"), Set.of("documentReminders"), census::documents, census::apply);
    }
    /** S16 is not implemented yet; register its explicit zero counter (E8-T06 step 1f). */
    @Bean ExpirationStep ringSetupExpirationStep() {
        return new Step('f', Module.COURSES, Set.of("EXPIRE_SETUP"), Set.of("expiredSetups"), context -> List.of(),
                (context, item) -> ExpirationsJob.NOT_IN_SCOPE);
    }
    record Step(char letter, Module module, Set<String> actions, Set<String> counters,
            Function<JobContext, List<JobItem>> planner, BiFunction<JobContext, JobItem, JobEffect> writer) implements ExpirationStep {
        @Override public List<JobItem> plan(JobContext context) { return planner.apply(context); }
        @Override public JobEffect apply(JobContext context, JobItem item) { return writer.apply(context, item); }
    }
}
