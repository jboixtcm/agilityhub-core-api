package com.agilityhub.core.platform.persistence.privacy;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.GlobalRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.bson.Document;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * Global: an account's request spans clubs (`clubId` null, `clubIds[]`). Every read for a club route filters on `clubIds`
 * explicitly (S14 §6: another club's member is a `404`). Every transition is a compare-and-set on `version` (T-14-24).
 */
@Repository
public class ErasureRequestRepository extends GlobalRepository<ErasureRequest> {
    public ErasureRequestRepository(MongoTemplate mongo) { super(mongo, ErasureRequest.class); }

    public void ensureIndexes() {
        var indexes = mongo.indexOps(ErasureRequest.class);
        indexes.createIndex(new Index().on("memberIds", Sort.Direction.ASC).unique()
                .partial(PartialIndexFilter.of(new Document("live", true))).named("erasure_live_member"));
        indexes.createIndex(new Index().on("clubIds", Sort.Direction.ASC).on("status", Sort.Direction.ASC).on("executeAt", Sort.Direction.ASC).named("erasure_club_due"));
        indexes.createIndex(new Index().on("accountId", Sort.Direction.ASC).on("status", Sort.Direction.ASC).named("erasure_account"));
        indexes.createIndex(new Index().on("requestedAt", Sort.Direction.DESC).named("erasure_requested"));
    }

    /** A second live request for one of its members → `409 ERASURE_ALREADY_REQUESTED` (the unique partial index, T-14-24). */
    public ErasureRequest insert(ErasureRequest request) {
        try { return mongo.insert(request); }
        catch (DuplicateKeyException duplicate) { throw new ApiException(ErrorCode.ERASURE_ALREADY_REQUESTED); }
    }
    public Optional<ErasureRequest> live(String memberId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("memberIds").is(memberId).and("live").is(true)), ErasureRequest.class));
    }
    public Optional<ErasureRequest> liveForAccount(String accountId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("accountId").is(accountId).and("live").is(true)), ErasureRequest.class));
    }
    /**
     * The requests the club's daily run executes (R-14-15): `SCHEDULED` or `BLOCKED` (the daily retry) with `executeAt ≤ now`
     * that still have members in the club to erase.
     */
    public List<ErasureRequest> due(String clubId, Instant now) {
        return mongo.find(Query.query(Criteria.where("status").in("SCHEDULED", "BLOCKED").and("executeAt").lte(now)
                .and("clubIds").is(clubId).and("erasedClubIds").ne(clubId)).with(Sort.by("executeAt", "_id")), ErasureRequest.class);
    }
    /** Compare-and-set on `version`: false when another writer moved the request first (T-14-24). */
    public boolean update(ErasureRequest current, Update update) {
        return mongo.updateFirst(Query.query(Criteria.where("_id").is(current.id()).and("version").is(current.version())),
                update.inc("version", 1), ErasureRequest.class).getModifiedCount() == 1;
    }
    /** One pending file deleted from its store. */
    public void fileDeleted(String id, String file, Instant now) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new Update().pull("filesPending", file).inc("version", 1), ErasureRequest.class);
    }
    /** `ERASING → ERASED` once no file is left (S14 §5); false when files remain or it is already closed. */
    public boolean close(String id, Instant now) {
        return mongo.findAndModify(Query.query(Criteria.where("_id").is(id).and("status").is("ERASING").and("filesPending").size(0)),
                new Update().set("status", "ERASED").set("erasedAt", now).unset("live").inc("version", 1),
                FindAndModifyOptions.options().returnNew(true), ErasureRequest.class) != null;
    }
    /** The club's requests stuck in `ERASING` (the daily run retries their files). */
    public List<ErasureRequest> erasing(String clubId) {
        return mongo.find(Query.query(Criteria.where("status").is("ERASING").and("clubIds").is(clubId)).with(Sort.by("executeAt", "_id")), ErasureRequest.class);
    }
    public List<ErasureRequest> ofMembers(Collection<String> memberIds) {
        return mongo.find(Query.query(Criteria.where("memberIds").in(memberIds)).with(Sort.by(Sort.Direction.DESC, "requestedAt")), ErasureRequest.class);
    }
    /** S17's console list (`/platform/erasure-requests`), newest first. */
    public List<ErasureRequest> page(List<Criteria> filters, int page, int size) {
        return mongo.find(query(filters).with(Sort.by(Sort.Direction.DESC, "requestedAt", "_id")).skip((long) page * size).limit(size), ErasureRequest.class);
    }
    public long count(List<Criteria> filters) { return mongo.count(query(filters), ErasureRequest.class); }
    private static Query query(List<Criteria> filters) { return filters.isEmpty() ? new Query() : Query.query(new Criteria().andOperator(filters)); }
}
