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
    public Map<String,Object> price(String id) { return one("prices", "_id", id); }
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
    public List<Map<String,Object>> approvedInactivity(String memberId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("state").in("APPROVED", "ACTIVE")), Document.class, "inactivity_periods").stream()
                .map(row -> object("from", YearMonth.parse(row.getString("fromMonth")).atDay(1),
                        "to", row.get("toMonth") == null ? null : YearMonth.parse(row.getString("toMonth")).atEndOfMonth())).toList();
    }
    public boolean inactive(String memberId, LocalDate today) {
        return approvedInactivity(memberId).stream().anyMatch(p -> !date(p.get("from")).isAfter(today) && (p.get("to") == null || !date(p.get("to")).isBefore(today)));
    }
    public LocalDate inactivityEnd(String memberId, LocalDate today) {
        return approvedInactivity(memberId).stream().filter(p -> !date(p.get("from")).isAfter(today) && (p.get("to") == null || !date(p.get("to")).isBefore(today)))
                .map(p -> Optional.ofNullable(date(p.get("to")))).findFirst().orElse(Optional.empty()).orElse(null);
    }
    public Map<String,Object> pack(String dogId, LocalDate today) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("dogId").is(dogId).and("state").is("ACTIVE").and("remaining").gt(0)
                .and("expiresOn").gte(today.toString())).with(Sort.by("expiresOn", "_id")), Document.class, "pack_balances").stream()
                .map(row -> object("id", row.get("_id"), "remaining", number(row.get("remaining")), "total", number(row.get("sessionsTotal")), "expiresOn", date(row.get("expiresOn"))))
                .findFirst().orElse(Map.of());
    }
    public Map<String,Object> packById(String id) { return one("pack_balances", "_id", id); }
    public boolean livePack(String memberId, LocalDate today) {
        return mongo.exists(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId).and("state").is("ACTIVE")
                .and("expiresOn").gte(today.toString())), "pack_balances");
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
    /**
     * D10's «Rebuts recents» over S12's `invoices` (E8-T02): the club-local `issueDate`, the invoice's `total`, newest first. A
     * receipt rolled back (S12 R-12-14: its run is `ROLLED_BACK`, E8-T07) is left out: the next run reissued its number (E87).
     */
    public List<Map<String,Object>> invoices(String memberId) {
        var query = tenantQuery().addCriteria(Criteria.where("memberId").is(memberId));
        var rolledBackRuns = mongo.find(tenantQuery().addCriteria(Criteria.where("status").is("ROLLED_BACK")), Document.class, "billing_runs").stream()
                .map(run -> run.getString("_id")).toList();
        if (!rolledBackRuns.isEmpty()) { query.addCriteria(Criteria.where("runId").nin(rolledBackRuns)); }
        return mongo.find(query.with(Sort.by(Sort.Direction.DESC, "issueDate").and(Sort.by(Sort.Direction.DESC, "number"))), Document.class, "invoices").stream()
                .map(row -> object("id", row.get("_id"), "date", date(row.get("issueDate")), "amount", row.get("total"), "status", row.get("status"))).toList();
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
