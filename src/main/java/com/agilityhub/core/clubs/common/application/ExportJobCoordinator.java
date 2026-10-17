package com.agilityhub.core.clubs.common.application;

import com.agilityhub.core.clubs.common.persistence.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.mongodb.core.query.Update;

/** Club-wide writes serialize admission/claims across API instances, not just scheduler threads. */
@Service
public class ExportJobCoordinator {
    private final ListExportRepository jobs; private final ExportWorkRepository work; private final TransactionTemplate transactions; private final Clock clock; private final ExportCompletion completion;
    public ExportJobCoordinator(ListExportRepository jobs, ExportWorkRepository work, PlatformTransactionManager manager, Clock clock, ExportCompletion completion) {
        this.jobs = jobs; this.work = work; this.transactions = new TransactionTemplate(manager); this.clock = clock; this.completion = completion;
    }
    public ExportJob submit(ExportJob job) {
        return transaction(() -> {
            work.lockAccount(job.ownerAccountId());
            jobs.lock(); jobs.recover(clock.instant());
            if (jobs.running() >= 2) { throw new ApiException(ErrorCode.EXPORT_LIMIT); }
            if (work.recent(job.ownerAccountId(), clock.instant().minusSeconds(600)) >= 10) { throw new ApiException(ErrorCode.RATE_LIMITED); }
            return jobs.insert(job);
        });
    }
    /**
     * S14 R-14-13: a member's data package, admitted like any export (account rate limit, club write lock) and at most one per
     * member every 24 h (`DATA_EXPORT_TOO_SOON`), checked under the same lock so two requests at once cannot both pass.
     */
    public ExportJob submitMemberData(ExportJob job, Instant since) {
        return transaction(() -> {
            work.lockAccount(job.ownerAccountId());
            jobs.lock(); jobs.recover(clock.instant());
            if (jobs.memberDataSince(job.subjectMemberId(), since)) { throw new ApiException(ErrorCode.DATA_EXPORT_TOO_SOON); }
            if (work.recent(job.ownerAccountId(), clock.instant().minusSeconds(600)) >= 10) { throw new ApiException(ErrorCode.RATE_LIMITED); }
            return jobs.insert(job);
        });
    }
    public ExportJob claim() {
        return transaction(() -> {
            jobs.lock(); jobs.recover(clock.instant());
            return jobs.running() >= 2 ? null : jobs.claim(clock.instant(), UUID.randomUUID().toString());
        });
    }
    public void heartbeat(ExportJob job, int progress) {
        // matched writes can be no-ops under a frozen test clock; an increment maintains a visible fence.
        if (!jobs.updateClaim(job, clock.instant(), new Update().set("leaseUntil", clock.instant().plusSeconds(300))
                .set("progressPct", Math.min(99, progress)).inc("heartbeatSequence", 1))) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }
    public void complete(ExportJob job, String fileKey, long size, long rows) {
        transaction(() -> {
            if (!jobs.updateClaim(job, clock.instant(), new Update().set("status", "READY").set("fileKey", fileKey)
                    .set("sizeBytes", size).set("rows", rows).set("progressPct", 100)
                    .set("expiresAt", clock.instant().plus(Duration.ofDays(7))).unset("errorCode").unset("leaseUntil").unset("claimToken"))) {
                throw new ApiException(ErrorCode.INVALID_STATE);
            }
            completion.completed(job.id(), job.listKey(), job.format().toLowerCase(Locale.ROOT), rows);
            return null;
        });
    }
    /** S14 R-14-13: the package is `READY` (7 days, `ExportPolicy.MEMBER_DATA_RETENTION`), audited and announced in the same transaction. */
    public void completeMemberData(ExportJob job, String fileKey, long size, long files) {
        transaction(() -> {
            if (!jobs.updateClaim(job, clock.instant(), new Update().set("status", "READY").set("fileKey", fileKey)
                    .set("sizeBytes", size).set("rows", files).set("progressPct", 100)
                    .set("expiresAt", clock.instant().plus(ExportPolicy.MEMBER_DATA_RETENTION)).unset("errorCode").unset("leaseUntil").unset("claimToken"))) {
                throw new ApiException(ErrorCode.INVALID_STATE);
            }
            completion.memberDataCompleted(job.id(), job.subjectMemberId(), files, Boolean.TRUE.equals(job.deliverToMember()));
            return null;
        });
    }
    public void fail(ExportJob job, RuntimeException error) {
        String code = error instanceof ApiException api ? api.code().name() : ErrorCode.INVALID_STATE.name();
        jobs.updateClaim(job, clock.instant(), new Update().set("status", job.attempts() >= 3 ? "FAILED" : "QUEUED")
                .set("errorCode", code).unset("leaseUntil").unset("claimToken"));
    }
    private <T> T transaction(Supplier<T> action) {
        for (int attempt = 0; ; attempt++) {
            try { return transactions.execute(status -> action.get()); }
            catch (RuntimeException ex) {
                if (attempt >= 9 || !transientConflict(ex)) { throw ex; }
                Thread.yield();
            }
        }
    }
    private boolean transientConflict(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof com.mongodb.MongoException mongo && (mongo.hasErrorLabel("TransientTransactionError") || mongo.getCode() == 11000)) { return true; }
        }
        return false;
    }
}
