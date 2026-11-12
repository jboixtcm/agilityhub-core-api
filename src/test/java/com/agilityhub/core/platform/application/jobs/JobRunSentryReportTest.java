package com.agilityhub.core.platform.application.jobs;

import com.agilityhub.core.platform.persistence.jobs.JobRun;
import io.sentry.IScopes;
import io.sentry.SentryEvent;
import io.sentry.SentryLevel;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * S15 R-15-07, R-15-10 (audit JOB-01): Sentry receives one event per FAILED or PARTIAL run, carrying the run's first exception
 * and its first error's trace id; SUCCEEDED and SKIPPED runs, and a context without Sentry, send nothing. The end-to-end
 * part (the call that closes the run reports it) is in `JobFrameworkIT.T_15_10_…`.
 */
class JobRunSentryReportTest {
    private static final Instant AT = Instant.parse("2026-10-05T05:30:00Z");

    private static JobRun run(JobStatus status, List<JobRun.RunError> errors) {
        return new JobRun("run-1", "club-a", JobName.TEST_NOOP, AT, "2026-10-05T07:30", "Europe/Madrid", JobTrigger.SCHEDULE, false,
                status, null, AT, AT, 0L, List.of(), List.of(), errors, null, List.of(), true, "holder", false);
    }

    @Test void T_15_10_aPartialRunSendsOneEventWithItsFirstExceptionAndTraceId() {
        var sentry = mock(IScopes.class);
        var failure = new IllegalStateException("fictional item failure");
        var errors = List.of(new JobRun.RunError("item-2", "INTERNAL_ERROR", "IllegalStateException", "trace-2"),
                new JobRun.RunError("item-3", "INTERNAL_ERROR", "IllegalStateException", "trace-3"));

        JobRunner.report(sentry, run(JobStatus.PARTIAL, errors), failure);

        var event = ArgumentCaptor.forClass(SentryEvent.class);
        verify(sentry, times(1)).captureEvent(event.capture());
        assertThat(event.getValue().getThrowable()).isSameAs(failure);
        assertThat(event.getValue().getLevel()).isEqualTo(SentryLevel.ERROR);
        assertThat(event.getValue().getTag("traceId")).isEqualTo("trace-2");
        assertThat(event.getValue().getMessage().getFormatted())
                .isEqualTo("Job run PARTIAL job=TEST_NOOP clubId=club-a jobRunId=run-1 errorCount=2");
    }

    @Test void T_15_10_aFailedRunWithoutExceptionStillSendsItsMessage() {
        var sentry = mock(IScopes.class);
        var reaped = List.of(new JobRun.RunError(null, "INTERNAL_ERROR", "Lease expired before the run finished", "trace-1"));

        JobRunner.report(sentry, run(JobStatus.FAILED, reaped), null);

        var event = ArgumentCaptor.forClass(SentryEvent.class);
        verify(sentry).captureEvent(event.capture());
        assertThat(event.getValue().getThrowable()).isNull();
        assertThat(event.getValue().getTag("traceId")).isEqualTo("trace-1");
        assertThat(event.getValue().getMessage().getFormatted()).startsWith("Job run FAILED job=TEST_NOOP");
    }

    @Test void T_15_10_succeededAndSkippedRunsOrNoSentrySendNothing() {
        var sentry = mock(IScopes.class);
        JobRunner.report(sentry, run(JobStatus.SUCCEEDED, List.of()), null);
        JobRunner.report(sentry, run(JobStatus.SKIPPED, List.of()), null);
        verify(sentry, never()).captureEvent(any(SentryEvent.class));
        assertThatCode(() -> JobRunner.report(null, run(JobStatus.PARTIAL, List.of()), new IllegalStateException("fictional")))
                .doesNotThrowAnyException();
        doThrow(new IllegalStateException("fictional transport failure")).when(sentry).captureEvent(any(SentryEvent.class));
        assertThatCode(() -> JobRunner.report(sentry, run(JobStatus.FAILED, List.of()), null)).doesNotThrowAnyException();
    }
}
