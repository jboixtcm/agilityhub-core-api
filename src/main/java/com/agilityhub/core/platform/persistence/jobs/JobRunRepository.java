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
    /**
     * R-15-04/R-15-06: the final write of a run (completion or reaping) replaces the row only while it is still RUNNING under
     * the same lease holder. False = someone else closed it first (the holder finished, or the reaper failed it).
     */
    public boolean finish(JobRun finished) {
        var query = tenantQuery(finished.clubId()).addCriteria(Criteria.where("_id").is(finished.id())
                .and("status").is(JobStatus.RUNNING).and("holder").is(finished.holder()));
        return mongo.findAndReplace(query, finished) != null;
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
    /** D11 `lastRun`: the latest finished execution of the process (SKIPPED rows included, RUNNING ones excluded). */
    public Optional<JobRun> last(JobName job) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("job").is(job).and("status").ne(JobStatus.RUNNING))
                .with(Sort.by(Sort.Direction.DESC, "startedAt", "_id")).limit(1), JobRun.class));
    }
    /** S17 health: the latest non-dry execution that says something about the process (neutral skips excluded). */
    public Optional<JobRun> lastExecution(JobName job, java.util.Collection<SkipReason> neutral) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("job").is(job).and("dryRun").is(false)
                .and("status").ne(JobStatus.RUNNING).and("skipReason").nin(neutral)).with(Sort.by(Sort.Direction.DESC, "startedAt", "_id")).limit(1), JobRun.class));
    }
    /** S17 health: the latest non-dry SUCCEEDED run of the process. */
    public Optional<JobRun> lastSuccess(JobName job) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("job").is(job).and("status").is(JobStatus.SUCCEEDED)
                .and("dryRun").is(false)).with(Sort.by(Sort.Direction.DESC, "startedAt", "_id")).limit(1), JobRun.class));
    }
    public List<JobRun> byIds(List<String> ids) {
        if (ids.isEmpty()) { return List.of(); }
        return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(ids)), JobRun.class);
    }
}
