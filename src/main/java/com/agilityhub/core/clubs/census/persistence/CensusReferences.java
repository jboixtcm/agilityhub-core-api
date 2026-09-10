package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Read-only integration projections; later verticals own their writes. All joins bind the tenant. */
@Repository
public class CensusReferences extends TenantRepository<Member> {
    public CensusReferences(MongoTemplate mongo) { super(mongo, Member.class); }
    public List<String> activeLevelIds() {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("active").is(true)).with(Sort.by("order","_id")),Document.class,"levels").stream().map(d -> d.getString("_id")).toList();
    }
    public Map<String,Object> level(String id) { return one("levels", "_id", id); }
    public Map<String,Object> plan(String id) { return one("plans", "_id", id); }
    public Map<String,Object> membership(String memberId) { return one("memberships", "memberId", memberId); }
    private Map<String,Object> one(String collection, String field, String id) {
        if (id == null) { return Map.of(); }
        var row = mongo.findOne(tenantQuery().addCriteria(Criteria.where(field).is(id)), Document.class, collection);
        return row == null ? Map.of() : row;
    }
    public List<Map<String,Object>> tasks(String dogId, Instant since) {
        var items = mongo.find(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("deletedAt").is(null))
                .with(Sort.by(Sort.Direction.DESC, "createdAt")), Document.class, "tasks");
        return items.stream().filter(row -> "PENDING".equals(row.get("state")) || ("DONE".equals(row.get("state"))
                        && instant(row.get("doneAt")) != null && !instant(row.get("doneAt")).isBefore(since)))
                .map(row -> {
                    var creator = map(row.get("createdBy")); String name = string(creator.get("displayName"));
                    if (name == null) { name = string(row.get("instructorName")); }
                    if (name == null) { name = string(one("instructors", "_id", string(row.get("instructorId"))).get("shortName")); }
                    long attachments = mongo.count(tenantQuery().addCriteria(Criteria.where("entityType").is("TASK")
                            .and("entityId").is(row.get("_id")).and("removedAt").is(null)), "attachments");
                    return object("id", row.get("_id"), "text", row.get("text"), "createdAt", instant(row.get("createdAt")),
                            "instructorName", name == null ? "" : name, "attachmentsCount", attachments, "doneAt", instant(row.get("doneAt")));
                }).toList();
    }
    public LocalDate inactivityEnd(String memberId, LocalDate today) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId)), Document.class, "inactivity_periods").stream()
                .filter(row -> !Set.of("CANCELLED", "REJECTED", "DENIED").contains(String.valueOf(row.get("status"))))
                .filter(row -> row.get("from") != null && row.get("to") != null)
                .filter(row -> !date(row.get("from")).isAfter(today) && !date(row.get("to")).isBefore(today))
                .map(row -> date(row.get("to"))).max(Comparator.naturalOrder()).orElse(null);
    }
    public Map<String,Object> pack(String dogId, LocalDate today) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId)), Document.class, "pack_balances").stream()
                .filter(row -> number(row.get("remaining")) > 0 && !Set.of("CLOSED", "EXPIRED", "CANCELLED").contains(String.valueOf(row.get("status"))))
                .filter(row -> row.get("expiresOn") == null || !date(row.get("expiresOn")).isBefore(today))
                .map(row -> object("id", row.get("_id"), "remaining", number(row.get("remaining")), "total", number(row.get("total")), "expiresOn", date(row.get("expiresOn"))))
                .findFirst().orElse(Map.of());
    }
    public List<Map<String,Object>> futureBookings(String dogId, Instant now) {
        var result = new ArrayList<Map<String,Object>>();
        for (String collection : List.of("bookings", "training_bookings", "waitlist_entries")) {
            for (var row : mongo.find(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId)), Document.class, collection)) {
                String state = string(row.getOrDefault("state", row.get("status")));
                if (state == null || !Set.of("ACTIVE", "CONFIRMED", "BOOKED", "NOTIFIED", "RESERVED").contains(state)) { continue; }
                Instant starts = instant(row.get("startsAt"));
                if (starts == null) {
                    String classId = string(row.getOrDefault("classSessionId", row.get("classId")));
                    starts = instant(one("class_sessions", "_id", classId).get("startsAt"));
                }
                // Unknown dates fail closed until the owning vertical supplies its final projection.
                if (starts == null || starts.isAfter(now)) { result.add(object("id", row.get("_id"), "kind", collection, "startsAt", starts)); }
            }
        }
        return List.copyOf(result);
    }
    public List<Map<String,Object>> invoices(String memberId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId))
                .with(Sort.by(Sort.Direction.DESC, "date")), Document.class, "invoices").stream()
                .map(row -> object("id", row.get("_id"), "date", date(row.get("date")), "amount", row.get("amount"), "status", row.get("status"))).toList();
    }
    public List<Map<String,Object>> recentAudit(String memberId) {
        return mongo.find(tenantQuery().addCriteria(new Criteria().orOperator(Criteria.where("memberId").is(memberId),
                        Criteria.where("impersonatedMemberId").is(memberId))).with(Sort.by(Sort.Direction.DESC, "at")).limit(2), Document.class, "audit_entries")
                .stream().map(row -> object("id", row.get("_id"), "at", instant(row.get("at")), "action", row.get("action"),
                        "actorRole", row.get("actorRole"), "actorName", row.get("actorName"))).toList();
    }
    public void lockLevelCatalog() {
        mongo.upsert(tenantQuery().addCriteria(Criteria.where("catalog").is("Level")), new Update().inc("sequence", 1), "catalog_write_locks");
    }
}
