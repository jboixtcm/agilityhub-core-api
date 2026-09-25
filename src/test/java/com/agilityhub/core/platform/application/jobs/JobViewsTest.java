package com.agilityhub.core.platform.application.jobs;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E5-T15 (review E5-T13 #2, AGENTS rule 4): the club's counters of a P9 run leave the whole platform pass out. */
class JobViewsTest {
    private static JobViews.JobRunView run(boolean dryRun, Map<String, Long> counters, List<String> itemTypes) {
        var items = itemTypes.stream().map(type -> new JobViews.JobEffectItem(type, type + "-id", dryRun ? "WOULD_DELETE" : "DELETE", null)).toList();
        var at = Instant.parse("2026-10-06T04:00:00Z");
        return new JobViews.JobRunView("run", "CLEANUP", at, "2026-10-06T06:00", "Europe/Madrid", JobTrigger.MANUAL, dryRun, JobStatus.SUCCEEDED, null,
                at, at, 0L, new JobViews.JobEffects(counters, items), List.of(), "admin", Map.of());
    }

    @Test void T_15_27_theClubsPlanCounterUsesThePlatformTotalRecordedBeforeTheTraceCut() {
        // 500 tenant items in the trace, the platform one cut off: the recorded platform total is what is subtracted.
        var counters = new LinkedHashMap<String, Long>(Map.of("WOULD_DELETE", 501L, "WOULD_DELETE_platformItems", 1L, "WOULD_DELETE_orphanUploads", 500L,
                "WOULD_DELETE_platformDomainEvents", 3L, "platformPass", 1L));
        var club = JobViews.forClub(run(true, counters, Collections.nCopies(500, "SignupUpload")));
        assertThat(club.effects().counters()).containsOnly(entry("WOULD_DELETE", 500L), entry("WOULD_DELETE_orphanUploads", 500L));
        assertThat(club.effects().items()).hasSize(500);
    }

    @Test void T_15_27_aRunStoredBeforeThePlatformTotalExistedStillSubtractsTheTracedPlatformItems() {
        var club = JobViews.forClub(run(true, Map.of("WOULD_DELETE", 3L, "platformPass", 1L), List.of("SignupUpload", "PlatformDomainEvents", "JobRuns")));
        assertThat(club.effects().counters()).containsOnly(entry("WOULD_DELETE", 2L));
        assertThat(club.effects().items()).extracting(JobViews.JobEffectItem::entityType).containsExactly("SignupUpload", "JobRuns");
    }

    @Test void T_15_27_aRealRunKeepsItsEffectCountersAndOnlyHidesThePlatformOnes() {
        var club = JobViews.forClub(run(false, Map.of("orphanUploadsDeleted", 2L, "platformDomainEventsDeleted", 4L, "platformPass", 1L),
                List.of("SignupUpload", "PlatformDomainEvents")));
        assertThat(club.effects().counters()).containsOnly(entry("orphanUploadsDeleted", 2L));
        assertThat(club.effects().items()).singleElement().satisfies(item -> assertThat(item.entityType()).isEqualTo("SignupUpload"));
        assertThat(JobViews.platformPlanCounter("DELETE")).isEqualTo("WOULD_DELETE_platformItems");
        assertThat(JobViews.platformCounter(JobViews.platformPlanCounter("DELETE"))).as("hidden from the club").isTrue();
        assertThat(JobViews.platformItem("PlatformJobRuns")).isTrue(); assertThat(JobViews.platformItem("JobRuns")).isFalse();
    }
}
