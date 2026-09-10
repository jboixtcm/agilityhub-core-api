package com.agilityhub.core.clubs.dashboard.persistence;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.clubs.dashboard.domain.DashboardPeriod;
import com.agilityhub.core.shared.domain.*;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.*;
import org.springframework.stereotype.Repository;

/** Read-only aggregation projections over census/catalog collections. Every lookup binds clubId. */
@Repository
public class DashboardRepository extends TenantRepository<DashboardRepository.Projection> {
    public record Projection(String id, String clubId) implements TenantEntity { }
    public DashboardRepository(MongoTemplate mongo) { super(mongo, Projection.class); }

    public ActiveMembers activeMembers(DashboardPeriod period) {
        Object joined = between(date("$joinedAt"), Date.from(period.monthFrom()), Date.from(period.monthUntil()));
        Object left = expr("$and", expr("$eq", "$status", "LEFT"), between(date("$leaveDate"),
                Date.from(period.today().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()),
                Date.from(period.today().withDayOfMonth(1).plusMonths(1).atStartOfDay(ZoneOffset.UTC).toInstant())));
        var rows = aggregate("members", List.of(new Document("$group", new Document("_id", null)
                .append("value", new Document("$sum", condition(expr("$eq", "$status", "ACTIVE"), 1, 0)))
                .append("joined", new Document("$sum", condition(joined, 1, 0)))
                .append("left", new Document("$sum", condition(left, 1, 0))))));
        if (rows.isEmpty()) { return new ActiveMembers(0, 0); }
        var row = rows.getFirst(); return new ActiveMembers(number(row, "value"), number(row, "joined") - number(row, "left"));
    }

    private List<Document> pendingStages() {
        return new ArrayList<>(List.of(new Document("$match", new Document("status", new Document("$in", List.of("PENDING", "ACTIVE")))),
                lookup("dogs", "$_id", "memberId", "pendingDogs", List.of(
                        new Document("$match", new Document("status", "PENDING")),
                        new Document("$sort", new Document("signup.submittedAt", 1).append("_id", 1)),
                        new Document("$project", new Document("name", 1).append("breed", 1).append("signup", 1).append("createdAt", 1)))),
                new Document("$match", new Document("$or", List.of(new Document("status", "PENDING"), new Document("pendingDogs.0", new Document("$exists", true)))))));
    }
    public int pendingCount() {
        var stages = pendingStages(); stages.add(new Document("$count", "value"));
        var rows = aggregate("members", stages); return rows.isEmpty() ? 0 : number(rows.getFirst(), "value");
    }
    public List<PendingSource> pendingSignups() {
        var stages = pendingStages();
        stages.add(new Document("$set", new Document("submission", condition(expr("$eq", "$status", "ACTIVE"),
                fallback(expr("$arrayElemAt", "$pendingDogs.signup", 0), "$signup"), "$signup"))));
        stages.add(lookup("plans", "$submission.planIdRequested", "_id", "requestedPlan", List.of(
                new Document("$project", new Document("name", 1)))));
        stages.add(new Document("$lookup", new Document("from", "dog_documents").append("let", new Document("ids", "$pendingDogs._id"))
                .append("pipeline", List.of(new Document("$match", tenantQuery().getQueryObject().append("state", "PENDING")
                        .append("$expr", expr("$in", "$dogId", "$$ids"))), new Document("$limit", 1), new Document("$project", new Document("_id", 1))))
                .append("as", "pendingDocuments")));
        stages.add(new Document("$lookup", new Document("from", "upfront_payments").append("let", new Document("ids", "$pendingDogs._id").append("member", "$_id"))
                .append("pipeline", List.of(new Document("$match", tenantQuery().getQueryObject()
                        .append("status", new Document("$nin", List.of("CANCELLED", "REFUNDED")))
                        .append("$expr", expr("$and", expr("$eq", "$memberId", "$$member"), expr("$in", "$dogId", "$$ids"),
                                expr("$gt", "$amountDue.amountMinor", "$amountPaid.amountMinor")))),
                        new Document("$limit", 1), new Document("$project", new Document("_id", 1)))).append("as", "unpaid")));
        Object imageEntries = new Document("$filter", new Document("input", "$consents").append("as", "entry")
                .append("cond", expr("$eq", "$$entry.type", "IMAGE_USE")));
        stages.add(new Document("$set", new Document("imageConsent", condition(new Document("$isArray", "$consents"),
                new Document("$let", new Document("vars", new Document("latest", expr("$arrayElemAt", imageEntries, -1)))
                        .append("in", fallback("$$latest.granted", false))), fallback("$consents.imageRights.granted", false)))));
        stages.add(new Document("$project", new Document("firstName", 1).append("lastName1", 1).append("status", 1)
                .append("pendingDogs.name", 1).append("pendingDogs.breed", 1).append("requestedPlan.name", 1)
                .append("paymentMethodType", "$paymentMethod.type").append("imageConsent", 1)
                .append("accountProvided", expr("$ne", fallback("$paymentMethod.iban", ""), ""))
                .append("documentPending", expr("$gt", expr("$size", "$pendingDocuments"), 0))
                .append("familyPending", expr("$eq", fallback("$familyGroupClaim.status", ""), "NOT_FOUND_PENDING"))
                .append("upfrontUnpaid", expr("$gt", expr("$size", "$unpaid"), 0))
                .append("readmission", fallback("$submission.readmission", false))
                .append("submittedAt", date(fallback("$submission.submittedAt", "$createdAt")))));
        return aggregate("members", stages).stream().map(row -> {
            boolean add = "ACTIVE".equals(row.getString("status"));
            var dogs = row.getList("pendingDogs", Document.class).stream().map(d -> new PendingDog(d.getString("name"),
                    d.getString("breed") == null ? "" : d.getString("breed"), add)).toList();
            var plan = row.getList("requestedPlan", Document.class);
            return new PendingSource(row.getString("_id"), row.getString("firstName"), row.getString("lastName1"), add, dogs,
                    plan.isEmpty() ? null : label(plan.getFirst().get("name")), row.getString("paymentMethodType"), row.getDate("submittedAt").toInstant(),
                    flag(row, "imageConsent"), flag(row, "accountProvided"), flag(row, "documentPending"), flag(row, "familyPending"),
                    flag(row, "upfrontUnpaid"), flag(row, "readmission"));
        }).toList();
    }
    public List<LevelSource> levels() {
        return aggregate("levels", List.of(new Document("$project", new Document("code", 1).append("name", 1).append("color", 1)
                .append("order", 1).append("active", 1)))).stream().map(row -> new LevelSource(row.getString("_id"), row.getString("code"),
                        label(row.get("name")), row.getString("color"), number(row, "order"), flag(row, "active"))).toList();
    }
    public List<DogCount> dogCounts(Set<String> recentDogs) {
        return aggregate("dogs", List.of(new Document("$match", new Document("status", "ACTIVE")),
                lookup("members", "$memberId", "_id", "owner", List.of(new Document("$match", new Document("status", "ACTIVE")),
                        new Document("$project", new Document("_id", 1)))),
                new Document("$match", new Document("owner.0", new Document("$exists", true))),
                new Document("$group", new Document("_id", "$levelId").append("total", new Document("$sum", 1))
                        .append("recent", new Document("$sum", condition(expr("$in", "$_id", new Document("$literal", new ArrayList<>(recentDogs))), 1, 0))))))
                .stream().map(row -> new DogCount(row.getString("_id"), number(row, "total"), number(row, "recent"))).toList();
    }
    private List<Document> aggregate(String collection, List<Document> stages) {
        var operations = new ArrayList<AggregationOperation>();
        operations.add(context -> new Document("$match", tenantQuery().getQueryObject()));
        stages.forEach(stage -> operations.add(context -> stage));
        return mongo.aggregate(Aggregation.newAggregation(operations), collection, Document.class).getMappedResults();
    }
    private Document lookup(String collection, Object local, String foreign, String alias, List<Document> more) {
        var stages = new ArrayList<Document>();
        stages.add(new Document("$match", tenantQuery().getQueryObject().append("$expr", expr("$eq", "$" + foreign, "$$reference")))); stages.addAll(more);
        return new Document("$lookup", new Document("from", collection).append("let", new Document("reference", local))
                .append("pipeline", stages).append("as", alias));
    }
    private static Document expr(String op, Object... values) { return new Document(op, Arrays.asList(values)); }
    private static Object condition(Object test, Object yes, Object no) { return expr("$cond", test, yes, no); }
    private static Object fallback(Object value, Object other) { return expr("$ifNull", value, other); }
    private static Object date(Object value) { return new Document("$convert", new Document("input", value).append("to", "date").append("onError", null).append("onNull", null)); }
    private static Object between(Object value, Object from, Object until) { return expr("$and", expr("$gte", value, from), expr("$lt", value, until)); }
    private static int number(Document row, String key) { return ((Number) row.getOrDefault(key, 0)).intValue(); }
    private static boolean flag(Document row, String key) { return Boolean.TRUE.equals(row.get(key)); }
    @SuppressWarnings("unchecked")
    private static LocalizedText label(Object raw) {
        var map = (Map<String, Object>) raw;
        Object values = map.getOrDefault("values", map);
        return LocalizedText.fromJson((Map<String, String>) values);
    }
}
