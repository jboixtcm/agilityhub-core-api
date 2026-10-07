package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.application.ports.LapsedCheckoutsPort;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.jobs.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * S15 R-15-15 P5 `expirations` (E5-T31, ruling E80): the steps run in the order of their letters, each only with its module, and
 * an item goes back to the step that planned it. Step h reaches the checkouts through {@link LapsedCheckoutsPort} only.
 */
class ExpirationsJobTest {
    static final Instant AT = Instant.parse("2026-10-06T04:00:00Z");

    static JobContext context(Set<Module> modules, boolean dryRun) {
        var config = new ClubConfig(null, Map.of(), modules, null, Map.of());
        var recorder = new JobRunRecorder() { public void parameter(String key, Object value) { } public void count(String key, long delta) { } };
        return new JobContext("club-a", ZoneId.of("Europe/Madrid"), AT, LocalDate.of(2026, 10, 6), dryRun, config, recorder, "run-1");
    }
    record Step(char letter, Module module, String entityType) implements ExpirationStep {
        public Set<String> actions() { return Set.of("EXPIRE_" + letter); }
        public List<JobItem> plan(JobContext context) { return List.of(new JobItem(entityType, entityType + "-1", "EXPIRE_" + letter)); }
        public JobEffect apply(JobContext context, JobItem item) { return JobEffect.of("EXPIRE_" + letter, "expired" + letter); }
    }

    @Test void T_15_23_memberStepsRouteByActionIncludingTheClubLevelSignupItem() {
        var job = new ExpirationsJob(List.of(new Step('c', null, "Member"), new Step('d', null, "Member")));
        var context = context(Set.of(), false);
        assertThat(job.apply(context, new JobItem("Member", "member-1", "EXPIRE_c")).counters())
                .containsExactly(Map.entry("expiredc", 1L));
        assertThat(job.apply(context, new JobItem("Member", "club-a", "EXPIRE_d")).counters())
                .containsExactly(Map.entry("expiredd", 1L));
        assertThat(job.apply(context, new JobItem("Member", "member-1", "UNKNOWN")))
                .isSameAs(ExpirationsJob.NOT_IN_SCOPE);
    }

    @Test void T_15_23_stepsRunInLetterOrderEachOnlyWithItsModule() {
        var job = new ExpirationsJob(List.of(new Step('h', Module.BILLING, "CheckoutSession"), new Step('f', Module.COURSES, "RingSetup"),
                new Step('g', null, "Club")));
        assertThat(job.name()).isEqualTo(JobName.EXPIRATIONS);
        assertThat(job.definition().module()).as("P5 itself has no module").isNull();
        var all = context(Set.of(Module.BILLING, Module.COURSES), false);
        assertThat(job.plan(all)).extracting(JobItem::entityType).containsExactly("RingSetup", "Club", "CheckoutSession");
        var billingOff = context(Set.of(Module.COURSES), false);
        assertThat(job.plan(billingOff)).extracting(JobItem::entityType).containsExactly("RingSetup", "Club");
        assertThat(job.plan(context(Set.of(), false))).extracting(JobItem::entityType).containsExactly("Club");
        assertThat(job.apply(all, new JobItem("CheckoutSession", "c-1", "EXPIRE_h")).counters()).containsEntry("expiredh", 1L);
        // An item of a step whose module went off, or of no step at all, is out of scope.
        assertThat(job.apply(billingOff, new JobItem("CheckoutSession", "c-1", "EXPIRE_h"))).isSameAs(ExpirationsJob.NOT_IN_SCOPE);
        assertThat(job.apply(all, new JobItem("Unknown", "u-1", "EXPIRE"))).isSameAs(ExpirationsJob.NOT_IN_SCOPE);
        assertThatThrownBy(() -> new ExpirationsJob(List.of(new Step('a', null, "Pack"), new Step('a', null, "Other"))))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EXPIRE_a");
    }

    @Test void T_15_23_stepHExpiresTheLapsedSignupCheckoutsThroughItsPort() {
        var port = mock(LapsedCheckoutsPort.class);
        when(port.lapsed(AT)).thenReturn(List.of(new LapsedCheckoutsPort.Lapsed("c-1", AT.minus(Duration.ofMinutes(90)).plusSeconds(20))));
        when(port.expire("c-1", AT)).thenReturn(true).thenReturn(false);
        var step = new SignupCheckoutExpiryStep(port);
        assertThat(step.letter()).isEqualTo('h'); assertThat(step.module()).isEqualTo(Module.BILLING);
        var job = new ExpirationsJob(List.of(step));
        var context = context(Set.of(Module.BILLING), false);
        assertThat(job.plan(context)).singleElement().satisfies(item -> {
            assertThat(item.entityType()).isEqualTo("CheckoutSession"); assertThat(item.entityId()).isEqualTo("c-1");
            assertThat(item.action()).isEqualTo("EXPIRE_CHECKOUT");
            assertThat(item.detail()).containsExactlyInAnyOrderEntriesOf(Map.of("checkoutSessionId", "c-1", "minutesExpired", 89L));
        });
        var item = job.plan(context).getFirst();
        var effect = job.apply(context, item);
        assertThat(effect.action()).isEqualTo("EXPIRE_CHECKOUT"); assertThat(effect.detail()).isEqualTo(item.detail());
        assertThat(effect.counters()).containsExactly(Map.entry("expiredCheckouts", 1L));
        assertThat(job.apply(context, item)).as("closed meanwhile").isSameAs(ExpirationsJob.NOT_IN_SCOPE);
        assertThat(job.plan(context(Set.of(), false))).as("BILLING off").isEmpty();
        verify(port, times(2)).lapsed(AT);
    }
}
