package com.agilityhub.core.platform.persistence.audit;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Read-only member existence/name projection; every lookup retains the audit entry's tenant. */
@Repository
public class AuditListProjection {
    private final MemberLookup members;
    public AuditListProjection(MongoTemplate mongo) { members = new MemberLookup(mongo); }
    public void requireMember(String id) {
        if (!members.exists(id)) { throw new ApiException(ErrorCode.NOT_FOUND); }
    }
    private static class MemberLookup extends TenantRepository<AuditEntry> {
        MemberLookup(MongoTemplate mongo) { super(mongo, AuditEntry.class); }
        boolean exists(String id) { return mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(id)), "members"); }
    }
    public List<Document> stages() {
        var result = new ArrayList<Document>();
        result.add(new Document("$lookup", new Document("from", "members")
                .append("let", new Document("member", "$impersonatedMemberId").append("club", "$clubId"))
                .append("pipeline", List.of(new Document("$match", new Document("$expr", new Document("$and", List.of(
                        new Document("$eq", List.of("$_id", "$$member")), new Document("$eq", List.of("$clubId", "$$club")))))),
                        new Document("$project", new Document("_id", 0).append("name", new Document("$trim", new Document("input",
                                new Document("$concat", List.of(ifNull("$firstName", ""), " ", ifNull("$lastName1", ""), " ", ifNull("$lastName2", "")))))))))
                .append("as", "auditMember")));
        Object origin = new Document("$switch", new Document("branches", List.of(
                branch(new Document("$ne", Arrays.asList(ifNull("$impersonatedMemberId", null), null)), "BACKOFFICE"),
                branch(new Document("$in", List.of("$actorRole", List.of("ADMIN", "PLATFORM", "INSTRUCTOR"))), "BACKOFFICE"),
                branch(new Document("$eq", List.of("$actorRole", "MEMBER")), "APP"),
                branch(new Document("$eq", List.of("$actorRole", "WEBHOOK")), "WEBHOOK"))).append("default", "SYSTEM"));
        // S14 §3: the audit origin is APP · BACKOFFICE · SYSTEM · WEBHOOK · PUBLIC. The writer stores the request's event origin,
        // where an instructor's action is INSTRUCTOR: it reads as BACKOFFICE, as for an entry without origin (E5-T22).
        Object stored = new Document("$cond", List.of(new Document("$eq", List.of("$origin", "INSTRUCTOR")), "BACKOFFICE", "$origin"));
        result.add(new Document("$set", new Document("origin", ifNull(stored, origin))
                .append("entityLabel", ifNull("$entityLabel", new Document("$concat", List.of("$entityType", " · ", "$entityId"))))
                .append("impersonatedName", ifNull("$impersonatedName", new Document("$arrayElemAt", List.of("$auditMember.name", 0))))
                .append("changes", changes()).append("eventIds", ifNull("$eventIds", List.of()))));
        return result;
    }
    /**
     * Every change carries `before` and `after`, `null` when there was no value (AuditChange requires both). The writer does not
     * store a `null`, so a created or cleared value has no key in Mongo (E5-T22, found by ListFieldsContractIT).
     */
    private static Document changes() {
        return new Document("$map", new Document("input", ifNull("$changes", List.of())).append("as", "change")
                .append("in", new Document("$mergeObjects", List.of(new Document("before", null).append("after", null), "$$change"))));
    }
    private static Document ifNull(Object value, Object fallback) { return new Document("$ifNull", Arrays.asList(value, fallback)); }
    private static Document branch(Object condition, Object value) { return new Document("case", condition).append("then", value); }
}
