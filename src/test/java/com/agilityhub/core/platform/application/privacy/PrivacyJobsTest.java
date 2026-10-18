package com.agilityhub.core.platform.application.privacy;

import com.agilityhub.core.platform.application.jobs.JobContext;
import com.agilityhub.core.platform.application.jobs.JobItem;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.application.jobs.JobRunRecorder;
import com.agilityhub.core.platform.persistence.audit.AuditRepository;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequestRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** S14 R-14-15 and R-14-16 through S15: the daily `erasures` run and the monthly `retention-sweep` (T-14-19, T-14-20). */
class PrivacyJobsTest {
    static final Instant AT = Instant.parse("2026-10-01T04:00:00Z");
    final ErasureRequestRepository requests = mock(ErasureRequestRepository.class);
    final ErasureExecutor executor = mock(ErasureExecutor.class);
    final ErasureFilesCleanup cleanup = mock(ErasureFilesCleanup.class);
    final ErasureDirectory directory = mock(ErasureDirectory.class);
    final ErasureService erasures = mock(ErasureService.class);
    final AuditRepository audit = mock(AuditRepository.class);
    final RetentionTask remittances = mock(RetentionTask.class);
    final JobRunRecorder recorder = mock(JobRunRecorder.class);

    JobContext context(boolean dryRun) {
        var config = new com.agilityhub.core.platform.application.ClubConfig(PrivacyFixtures.config("club-a").club(),
                Map.of("rgpd.retentionYearsAfterLeave", 6, "rgpd.rejectedSignupRetentionDays", 365, "audit.retentionYears", 6), java.util.Set.of(), null, Map.of());
        return new JobContext("club-a", ZoneId.of("Europe/Madrid"), AT, LocalDate.of(2026, 10, 1), dryRun, config, recorder, "run-1");
    }
    static ErasureMember left(String id, LocalDate leaveDate, String reason) {
        return new ErasureMember(id, id + "-account", 1, "LEFT", leaveDate, null, reason, null, List.of());
    }

    @Test void T_14_19_theDailyRunExecutesDueRequestsAndRetriesTheFilesOfErasingOnes() {
        var job = new ErasuresJob(requests, executor, cleanup);
        assertThat(job.name()).isEqualTo(JobName.ERASURES);
        when(requests.due("club-a", AT)).thenReturn(List.of(PrivacyFixtures.request("due", "MEMBER", "SCHEDULED", List.of("m"), List.of("club-a"), List.of(), null)));
        when(requests.toClean("club-a")).thenReturn(List.of(PrivacyFixtures.request("stuck", "MEMBER", "ERASING", List.of("n"), List.of("club-a"), List.of(), null)));
        var items = job.plan(context(false));
        assertThat(items).extracting(JobItem::entityId, JobItem::action).containsExactly(tuple("due", "ERASE"), tuple("stuck", "DELETE_FILES"));
        verify(recorder).count("erased", 0); verify(recorder).count("blocked", 0);
        when(executor.execute("due")).thenReturn(ErasureExecutor.Outcome.BLOCKED);
        assertThat(job.apply(context(false), items.get(0)).counters()).containsEntry("blocked", 1L);
        when(cleanup.clean("stuck")).thenReturn(true);
        assertThat(job.apply(context(false), items.get(1)).counters()).containsEntry("filesCleaned", 1L);
        when(cleanup.clean("stuck")).thenReturn(false);
        assertThat(job.apply(context(false), items.get(1)).action()).isEqualTo("NOT_IN_SCOPE");
    }

    @Test void T_14_20_theSweepFindsLeftMembersRejectedSignupsOwnedTasksAndOldAuditEntries() {
        var job = new RetentionSweepJob(directory, requests, erasures, executor, audit, List.of(remittances));
        assertThat(job.name()).isEqualTo(JobName.RETENTION_SWEEP);
        when(remittances.entityType()).thenReturn("Remittance"); when(remittances.action()).thenReturn("DELETE_REMITTANCE_FILE");
        when(remittances.counter()).thenReturn("remittanceFilesDeleted"); when(remittances.due(any())).thenReturn(List.of("remittance-2020"));
        when(directory.retentionDue(LocalDate.of(2020, 10, 1), AT.minusSeconds(365L * 24 * 3600))).thenReturn(List.of(
                left("left-2020", LocalDate.of(2020, 8, 31), "LEAVE_REQUEST"), left("rejected", null, "SIGNUP_REJECTED"), left("requested", LocalDate.of(2020, 1, 1), "ADMIN"),
                left("blocked", LocalDate.of(2020, 1, 1), "ADMIN")));
        when(requests.live("left-2020")).thenReturn(Optional.empty()); when(requests.live("rejected")).thenReturn(Optional.empty());
        when(requests.live("requested")).thenReturn(Optional.of(PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("requested"), List.of("club-a"), List.of(), null)));
        when(requests.live("blocked")).thenReturn(Optional.of(PrivacyFixtures.request("b", "MEMBER", "BLOCKED", List.of("blocked"), List.of("club-a"), List.of(), null)));
        Instant auditCutoff = LocalDate.of(2020, 10, 1).atStartOfDay(ZoneId.of("Europe/Madrid")).toInstant();
        when(audit.countBefore(auditCutoff)).thenReturn(3L);
        var items = job.plan(context(false));
        assertThat(items).extracting(JobItem::entityId, JobItem::action).containsExactly(tuple("left-2020", "ERASE_RETENTION"),
                tuple("rejected", "ERASE_REJECTED_SIGNUP"), tuple("blocked", "ERASE_RETENTION"), tuple("remittance-2020", "DELETE_REMITTANCE_FILE"),
                tuple(auditCutoff.toString(), "PURGE_AUDIT"));
        verify(recorder).count("remittanceFilesDeleted", 0);
    }

    @Test void T_14_20_eachSweepItemErasesPurgesOrRunsItsTask() {
        var job = new RetentionSweepJob(directory, requests, erasures, executor, audit, List.of(remittances));
        var member = left("left-2020", LocalDate.of(2020, 8, 31), "LEAVE_REQUEST");
        when(directory.member("left-2020")).thenReturn(Optional.of(member));
        when(requests.live("left-2020")).thenReturn(Optional.empty());
        var request = PrivacyFixtures.request("r", "MEMBER", "SCHEDULED", List.of("left-2020"), List.of("club-a"), List.of(), null);
        when(erasures.requestRetention(member, "retention-sweep")).thenReturn(request);
        when(executor.execute("r")).thenReturn(ErasureExecutor.Outcome.ERASED);
        assertThat(job.apply(context(false), new JobItem("Member", "left-2020", "ERASE_RETENTION")).counters()).containsEntry("membersErased", 1L);
        when(executor.execute("r")).thenReturn(ErasureExecutor.Outcome.BLOCKED);
        assertThat(job.apply(context(false), new JobItem("Member", "left-2020", "ERASE_REJECTED_SIGNUP")).counters()).containsEntry("erasuresBlocked", 1L);
        when(executor.execute("r")).thenReturn(ErasureExecutor.Outcome.ERASED);
        assertThat(job.apply(context(false), new JobItem("Member", "left-2020", "ERASE_REJECTED_SIGNUP")).counters()).containsEntry("rejectedSignupsErased", 1L);
        when(directory.member("gone")).thenReturn(Optional.empty());
        assertThat(job.apply(context(false), new JobItem("Member", "gone", "ERASE_RETENTION")).action()).isEqualTo("NOT_IN_SCOPE");
        when(audit.purgeBefore(Instant.parse("2020-09-30T22:00:00Z"))).thenReturn(3L);
        assertThat(job.apply(context(false), new JobItem("AuditEntry", "2020-09-30T22:00:00Z", "PURGE_AUDIT")).counters()).containsEntry("auditEntriesPurged", 3L);
        when(remittances.entityType()).thenReturn("Remittance"); when(remittances.action()).thenReturn("DELETE_REMITTANCE_FILE");
        when(remittances.counter()).thenReturn("remittanceFilesDeleted"); when(remittances.apply(any(), eq("x"))).thenReturn(true);
        assertThat(job.apply(context(false), new JobItem("Remittance", "x", "DELETE_REMITTANCE_FILE")).counters()).containsEntry("remittanceFilesDeleted", 1L);
        assertThat(job.apply(context(false), new JobItem("Other", "x", "UNKNOWN")).action()).isEqualTo("NOT_IN_SCOPE");
    }
}
