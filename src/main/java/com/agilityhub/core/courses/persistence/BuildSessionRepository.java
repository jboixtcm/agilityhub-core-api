package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.CourseParts.BuildParticipant;
import com.agilityhub.core.courses.domain.CourseTypes.BuildStatus;
import com.agilityhub.core.courses.domain.CourseTypes.ObstacleStatus;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

/**
 * S16 R-16-11 `build_sessions`. Every change is one conditional update on an open session (`NOT_STARTED` or `IN_PROGRESS`) that
 * increments `version` and returns the new document; an empty result means the session is not open any more (or not this club's).
 */
@Repository
public class BuildSessionRepository extends CourseTenantRepository<BuildSession> {
    static final List<String> OPEN = List.of(BuildStatus.NOT_STARTED.name(), BuildStatus.IN_PROGRESS.name());
    public BuildSessionRepository(MongoTemplate mongo) {
        super(mongo, BuildSession.class);
        mongo.indexOps(BuildSession.class).ensureIndex(new Index().on("clubId", ASC).on("placementId", ASC).named("session_placement"));
        mongo.indexOps(BuildSession.class).ensureIndex(new Index().on("clubId", ASC).on("status", ASC).on("startedAt", ASC).named("session_cleanup"));
        mongo.indexOps(BuildSession.class).ensureIndex(new Index().on("clubId", ASC).on("joinCode", ASC).unique().partial(PartialIndexFilter.of(Criteria.where("status").in("NOT_STARTED", "IN_PROGRESS"))).named("open_join_code"));
    }
    public Optional<BuildSession> findOpenByCode(String code) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("joinCode").is(code)
                .and("status").in(OPEN)), BuildSession.class));
    }
    /** The open session whose QR carries {@code token} (`joinToken`). */
    public Optional<BuildSession> findOpenByToken(String token) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("joinToken").is(token).and("status").in(OPEN)), BuildSession.class));
    }
    /** R-16-11 last-write-wins: the obstacle takes {@code status}; the session becomes `IN_PROGRESS` on its first change. */
    public Optional<BuildSession> markObstacle(String id, String obstacleId, ObstacleStatus status, String actor, Instant at) {
        var query = open(id).addCriteria(Criteria.where("obstacles.obstacleId").is(obstacleId));
        var update = touched(actor, at).set("obstacles.$.status", status).set("obstacles.$.byAccountId", actor).set("obstacles.$.at", at)
                .set("status", BuildStatus.IN_PROGRESS);
        return modify(query, update);
    }
    /** R-16-11 `finish` (`COMPLETED`, `finishedAt`) or S15 P9 (`ABANDONED`): the session closes, once. */
    public Optional<BuildSession> close(String id, BuildStatus status, String actor, Instant at) {
        return modify(open(id), touched(actor, at).set("status", status).set("finishedAt", at));
    }
    /** P9: closes the session only while it is still open and started (else created) before {@code cutoff}. */
    public Optional<BuildSession> abandon(String id, Instant cutoff, Instant at) {
        var query = open(id).addCriteria(new Criteria().orOperator(Criteria.where("startedAt").lte(cutoff),
                Criteria.where("startedAt").is(null).and("createdAt").lte(cutoff)));
        return modify(query, touched(null, at).set("status", BuildStatus.ABANDONED).set("finishedAt", at));
    }
    /** A participant joins once: a second join of the same account changes nothing (empty result). */
    public Optional<BuildSession> join(String id, BuildParticipant participant, Instant at) {
        var query = open(id).addCriteria(Criteria.where("participants.accountId").ne(participant.accountId()));
        return modify(query, touched(participant.accountId(), at).push("participants", participant));
    }
    /** S15 P9: the club's open sessions started (else created) before {@code cutoff}, oldest first. */
    public List<BuildSession> outlived(Instant cutoff) {
        var query = tenantQuery().addCriteria(Criteria.where("status").in(OPEN)).addCriteria(new Criteria().orOperator(
                Criteria.where("startedAt").lte(cutoff), Criteria.where("startedAt").is(null).and("createdAt").lte(cutoff)));
        return mongo.find(query.with(org.springframework.data.domain.Sort.by("startedAt", "_id")), BuildSession.class);
    }
    private Query open(String id) { return tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").in(OPEN)); }
    private static Update touched(String actor, Instant at) { return new Update().inc("version", 1).set("updatedAt", at).set("updatedByAccountId", actor); }
    private Optional<BuildSession> modify(Query query, Update update) {
        return Optional.ofNullable(mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), BuildSession.class));
    }
}
