package com.agilityhub.core.platform.persistence.jobs;

import com.agilityhub.core.platform.application.jobs.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

@Repository
public class JobRunRepository extends TenantRepository<JobRun> {
    public JobRunRepository(MongoTemplate mongo) { super(mongo, JobRun.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(JobRun.class);
        // R-15-04 level 1: one claimed execution per occurrence, even with two instances.
        indexes.ensureIndex(new Index().on("clubId", ASC).on("job", ASC).on("scheduledFor", ASC).on("trigger", ASC).unique()
                .partial(PartialIndexFilter.of(Criteria.where("exclusive").is(true))).named("job_run_occurrence"));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("job", ASC).on("startedAt", ASC).named("job_run_club_job_started"));
        indexes.ensureIndex(new Index().on("finishedAt", ASC).named("job_run_finished"));
    }

    /**
     * An occurrence is taken once a scheduled row covers it or a later one (the tick never goes back in time, so moving
     * `jobs.dailyTime` does not resurrect yesterday's occurrence at the new hour). SKIPPED{LOCKED} rows and runs reaped
     * after their lease expired do not count: the next tick retries them.
     */
    public boolean occurrenceTaken(JobName job, Instant scheduledFor) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("job").is(job).and("scheduledFor").gte(scheduledFor).and("dryRun").is(false)
                .and("trigger").in(JobTrigger.SCHEDULE, JobTrigger.CATCH_UP).and("skipReason").ne(SkipReason.LOCKED).and("leaseExpired").ne(true)), JobRun.class);
    }
    public boolean skippedSince(JobName job, SkipReason reason, Instant since) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("job").is(job).and("status").is(JobStatus.SKIPPED)
                .and("skipReason").is(reason).and("startedAt").gt(since)), JobRun.class);
    }
    public List<JobRun> running(JobName job) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("job").is(job).and("status").is(JobStatus.RUNNING)), JobRun.class);
    }
    public List<JobRun> forJob(JobName job) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("job").is(job)).with(Sort.by(Sort.Direction.DESC, "startedAt")), JobRun.class);
    }
    public Optional<JobRun> forJob(JobName job, String runId) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(runId).and("job").is(job)), JobRun.class));
    }
}
