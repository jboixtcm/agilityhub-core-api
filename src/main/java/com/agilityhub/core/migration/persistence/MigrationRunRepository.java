package com.agilityhub.core.migration.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.migration.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class MigrationRunRepository extends TenantRepository<MigrationRun> {
    private static final String LOCKS = "migration_write_locks";
    private static final Duration LEASE = Duration.ofMinutes(10);
    private final MigrationResetRepository reset;
    public MigrationRunRepository(MongoTemplate mongo,MigrationResetRepository reset) { super(mongo, MigrationRun.class); this.reset=reset; }

    /** Claim outside a transaction. An expired process can never write with its old holder again. */
    public void acquire(String holder, Instant now) {
        try {
            mongo.upsert(lockQuery().addCriteria(new Criteria().orOperator(Criteria.where("expiresAt").lte(now),
                            Criteria.where("expiresAt").exists(false))),
                    new Update().set("holder", holder).set("expiresAt", now.plus(LEASE)).inc("sequence", 1), LOCKS);
        } catch (DuplicateKeyException held) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
    }

    public boolean tryFence(String holder, Instant now) {
        return mongo.updateFirst(lockQuery().addCriteria(Criteria.where("holder").is(holder).and("expiresAt").gt(now)),
                new Update().set("expiresAt", now.plus(LEASE)).inc("sequence", 1), LOCKS).getMatchedCount() == 1;
    }

    /** Fence both before and after every transaction's work, including completion and failure recording. */
    public void fence(String holder, Instant now) {
        if (!tryFence(holder, now)) { throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }
    }

    public List<MigrationRun> interrupted(boolean production) {
        if (production && mongo.exists(tenantQuery().addCriteria(Criteria.where("env").is("PRODUCTION")
                .orOperator(Criteria.where("status").in("COMPLETED", "RECONCILED"),Criteria.where("execution").ne(null))), MigrationRun.class)) {
            throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED);
        }
        var latestByRoot=new HashMap<String,MigrationRun>();
        for(var run:mongo.find(tenantQuery().addCriteria(Criteria.where("execution").ne(null)),MigrationRun.class)) {
            latestByRoot.merge(run.execution().rootRunId(),run,(left,right)->left.execution().ordinal()>right.execution().ordinal() ? left : right);
        }
        if (latestByRoot.values().stream().anyMatch(run->!run.execution().pipelineComplete())) { throw alreadyApplied(); }
        return interruptedForExecution();
    }

    /** Advanced engine already resolved explicit resume/approval under its fenced lease. */
    public List<MigrationRun> interruptedForExecution() {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("status").is("RUNNING")),MigrationRun.class);
    }

    /** Read-only preview; apply repeats this resolution while holding the migration lease/write fence. */
    public MigrationExecution previewExecution(MigrationExecutionRequest request,InputManifest manifest,LocalDate cutover,boolean production,Instant now) {
        return authorizeExecution("PREVIEW",request,manifest,cutover,production,now);
    }

    /**
     * Resolves a new attempt against immutable prior attempts. The caller inserts it in the same fenced transaction,
     * so the ordinal is unambiguous even when timestamps are equal. This method itself performs no writes.
     */
    public MigrationExecution authorizeExecution(String runId,MigrationExecutionRequest request,InputManifest manifest,
            LocalDate cutover,boolean production,Instant now) {
        var history=mongo.find(tenantQuery().addCriteria(Criteria.where("source").is("PLAYOFF").and("mode").is("APPLY")),MigrationRun.class);
        long ordinal=Math.addExact(history.stream().mapToLong(run->run.execution()==null ? 0 : run.execution().ordinal()).max().orElse(0),1);
        String env=production ? "PRODUCTION" : "STAGING";
        var prior=latest(history.stream().filter(run->env.equals(run.env())).toList());
        var completed=new TreeSet<Integer>(); String root=runId, previous=null, approvalDigest=null;
        if (request.resumeRunId()!=null) {
            if (prior==null || !prior.id().equals(request.resumeRunId()) || prior.execution()==null) { throw alreadyApplied(); }
            if (!Objects.equals(prior.inputManifest(),manifest) || !Objects.equals(prior.execution().cutover(),cutover)) {
                throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH);
            }
            completed.addAll(prior.execution().completedSteps());
            var selectedWrites=new TreeSet<>(request.steps().values()); selectedWrites.remove(1);
            if (selectedWrites.isEmpty() || selectedWrites.stream().anyMatch(completed::contains)) { throw alreadyApplied(); }
            root=prior.execution().rootRunId(); previous=prior.id();
        } else if (request.approval()!=null) {
            if (!production || prior==null || !request.approval().matches(TenantContext.require(),prior.id(),manifest,cutover,
                    request.steps(),now,prior.finishedAt()==null ? prior.startedAt() : prior.finishedAt())) {
                throw new ApiException(ErrorCode.PRODUCTION_REQUIRES_CONFIRMATION);
            }
            previous=prior.id(); approvalDigest=request.approval().digest();
        } else {
            // A completed legacy production load is full; every advanced attempt requires an explicit continuation/reapply.
            if (production && history.stream().anyMatch(run->"PRODUCTION".equals(run.env())
                    && (run.execution()!=null || Set.of("COMPLETED","RECONCILED").contains(run.status())))) { throw alreadyApplied(); }
            if (request.staged() && prior!=null && prior.execution()!=null && !prior.execution().pipelineComplete()) { throw alreadyApplied(); }
        }
        return new MigrationExecution(request.steps().values(),new TreeSet<>(),completed,cutover,root,previous,ordinal,approvalDigest,request.staged());
    }

    /** Completed only after the whole selected step succeeds; the caller checkpoints this metadata in its transaction. */
    public void stepCompleted(String runId,int step) {
        var run=findById(runId).orElseThrow(()->new ApiException(ErrorCode.NOT_FOUND));
        if (run.execution()==null || !"RUNNING".equals(run.status())) { throw new ApiException(ErrorCode.INVALID_STATE); }
        var execution=run.execution().stepCompleted(step);
        if (mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(runId).and("status").is("RUNNING")),
                new Update().set("execution",execution),MigrationRun.class).getMatchedCount()!=1) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }

    private ApiException alreadyApplied() { return new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED); }

    private MigrationRun latest(List<MigrationRun> history) {
        if (history.isEmpty()) { return null; }
        long ordinal=history.stream().filter(run->run.execution()!=null).mapToLong(run->run.execution().ordinal()).max().orElse(0);
        List<MigrationRun> candidates;
        if (ordinal>0) {
            candidates=history.stream().filter(run->run.execution()!=null && run.execution().ordinal()==ordinal).toList();
        } else {
            // Legacy records have no ordinal. A timestamp tie cannot establish which approval is current.
            var latest=history.stream().map(MigrationRun::startedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
            candidates=history.stream().filter(run->Objects.equals(run.startedAt(),latest)).toList();
        }
        if (candidates.size()!=1) { throw alreadyApplied(); }
        return candidates.getFirst();
    }

    /** Reset uses the same document lock as import batches and refuses a live import owner. */
    public void lockReset(Instant now) {
        mongo.upsert(lockQuery(), new Update().inc("sequence", 1), LOCKS);
        if (mongo.exists(lockQuery().addCriteria(Criteria.where("expiresAt").gt(now)), LOCKS)) {
            throw new ApiException(ErrorCode.MIGRATION_ALREADY_APPLIED);
        }
    }

    public void release(String holder) {
        mongo.updateFirst(lockQuery().addCriteria(Criteria.where("holder").is(holder)),
                new Update().unset("holder").unset("expiresAt"), LOCKS);
    }

    @org.springframework.transaction.annotation.Transactional
    public boolean reportPublished(String id, String key, Instant now) {
        return reset.publishReportMetadata(()->mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("reportExpiresAt").gt(now)
                .and("reportPurgedAt").is(null)), new Update().set("reportFileKey",key), MigrationRun.class).getModifiedCount()==1);
    }
    public List<MigrationRun> expiredReports(Instant now) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("env").is("PRODUCTION").and("reportExpiresAt").lte(now)
                .and("reportPurgedAt").is(null)),MigrationRun.class);
    }
    public boolean reportPurged(String id, Instant now) {
        return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("reportExpiresAt").lte(now)
                .and("reportPurgedAt").is(null)),new Update().unset("reportFileKey").set("reportPurgedAt",now),MigrationRun.class).getModifiedCount()==1;
    }

    private Query lockQuery() { return tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require())); }
}
