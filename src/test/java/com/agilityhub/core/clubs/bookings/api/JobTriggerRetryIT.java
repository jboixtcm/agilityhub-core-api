package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.application.jobs.JobStatus;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.IdempotencyRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpMethod.*;

/**
 * E5-T29 round 2 (review #1; CONVENCIONS_API §7, S15 R-15-09, ruling E75): a keyed `POST /jobs/{name}/trigger` commits its run and
 * its JOB_TRIGGERED entry outside the request's transaction, before the key's answer is stored. When that answer is lost (here the
 * write of the answer fails: the request's transaction rolls back and the key is released), the retry with the same key and body
 * answers the run the first attempt started, and runs and audits nothing.
 */
class JobTriggerRetryIT extends BookingFixtures {
    @MockitoSpyBean IdempotencyRepository records;

    @BeforeEach void runs() {
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "job_runs");
        mongo.remove(new Query(), "job_locks");
    }
    private List<JobRun> cleanupRuns() { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("job").is(JobName.CLEANUP)), JobRun.class); }
    private String requestRef(String runId) { return mongo.findById(runId, org.bson.Document.class, "job_runs").getString("requestRef"); }
    private long triggered() { return mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("JOB_TRIGGERED")), "audit_entries"); }
    /** The first attempt: the run commits, then storing its answer fails, so the client gets an error and the key is released. */
    private JobRun lostAnswer(boolean dryRun, String key) throws Exception {
        var before = cleanupRuns().stream().map(JobRun::id).toList();
        doThrow(new ApiException(ErrorCode.STALE_VERSION)).doCallRealMethod().when(records).complete(any(), anyInt(), any(), any());
        assertThat(code(call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", dryRun), as("admin"), 409, key))).isEqualTo("STALE_VERSION");
        var started = cleanupRuns().stream().filter(run -> !before.contains(run.id())).toList();
        assertThat(started).as("the first attempt's run committed").singleElement()
                .satisfies(run -> { assertThat(run.status()).isEqualTo(JobStatus.SUCCEEDED); assertThat(run.dryRun()).isEqualTo(dryRun); });
        return started.getFirst();
    }

    @Test void T_15_08_CONVENCIONS_API_7_aKeyedTriggerWhoseAnswerWasLostAnswersItsOwnRunToTheRetry() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = lostAnswer(false, key);
        assertThat(triggered()).isEqualTo(1);
        JsonNode retry = call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", false), as("admin"), 200, key);
        assertThat(retry.path("runId").asText()).isEqualTo(first.id());
        assertThat(retry.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(retry.path("dryRun").asBoolean()).isFalse();
        assertThat(retry.at("/effects/counters")).isEqualTo(call(GET, "/jobs/cleanup/runs/" + first.id(), null, as("admin"), 200).at("/effects/counters"));
        assertThat(cleanupRuns()).as("one run").hasSize(1);
        assertThat(triggered()).as("one JOB_TRIGGERED entry").isEqualTo(1);
        assertThat(requestRef(first.id())).as("a digest names the request, never the key").isNotBlank().doesNotContain(key);
        // The retry's answer was stored: the key replays it.
        assertThat(call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", false), as("admin"), 200, key)).isEqualTo(retry);
        assertThat(cleanupRuns()).hasSize(1);
        // A keyed dry run is answered the same way.
        String dryKey = UUID.randomUUID().toString();
        var dry = lostAnswer(true, dryKey);
        var dryRetry = call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", true), as("admin"), 200, dryKey);
        assertThat(dryRetry.path("runId").asText()).isEqualTo(dry.id());
        assertThat(dryRetry.path("dryRun").asBoolean()).isTrue();
        assertThat(cleanupRuns()).hasSize(2);
        assertThat(triggered()).isEqualTo(2);
        // Another key is another request: it runs the process again.
        var other = call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", false), as("admin"), 200, UUID.randomUUID().toString());
        assertThat(other.path("runId").asText()).isNotIn(first.id(), dry.id());
        assertThat(cleanupRuns()).hasSize(3);
        assertThat(triggered()).isEqualTo(3);
        // A run without a key keeps no reference.
        var plain = call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", true), as("admin"), 200);
        assertThat(requestRef(plain.path("runId").asText())).isNull();
    }

    /** The key lives 24 h (CONVENCIONS_API §7): once it has expired, the same key and body are a new request and run again. */
    @Test void T_15_08_CONVENCIONS_API_7_aRunOlderThanTheKeysLifetimeIsNotAnswered() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = lostAnswer(false, key);
        clock.setInstant(clock.instant().plus(java.time.Duration.ofHours(24)).plusSeconds(60));
        var later = call(POST, "/jobs/cleanup/trigger", Map.of("dryRun", false), as("admin"), 200, key);
        assertThat(later.path("runId").asText()).isNotEqualTo(first.id());
        assertThat(cleanupRuns()).hasSize(2);
    }
}
