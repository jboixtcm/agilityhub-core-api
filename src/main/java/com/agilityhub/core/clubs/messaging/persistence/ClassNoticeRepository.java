package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.aggregation.AggregationOperation;
import org.springframework.stereotype.Repository;

/** Administrator class notices, one occurrence regardless of the number of staff recipients. */
@Repository
public class ClassNoticeRepository extends TenantRepository<Notification> {
    public ClassNoticeRepository(MongoTemplate mongo) { super(mongo, Notification.class); }
    public record Page(List<Notification> items, long totalItems) { }

    public Page page(Instant from, Instant until, int page, int size) {
        var scope = tenantQuery().getQueryObject();
        scope.put("audience", "ADMINS");
        scope.put("code", new Document("$in", List.of("N-08a", "N-16", "N-17", "N-54")));
        scope.put("subject.classSessionId", new Document("$type", "string"));
        scope.put("deliveries", new Document("$elemMatch", new Document("status", new Document("$in", List.of("SENT", "DELIVERED")))));
        var stages = new ArrayList<Document>();
        stages.add(new Document("$match", scope));
        // Keep the first frozen copy. A delayed recipient never moves an occurrence into a later day.
        stages.add(new Document("$sort", new Document("createdAt", 1).append("_id", 1)));
        stages.add(new Document("$group", new Document("_id", new Document("event", new Document("$ifNull", List.of("$eventId", "$_id")))
                .append("code", "$code").append("classId", "$subject.classSessionId")).append("notice", new Document("$first", "$$ROOT"))));
        stages.add(new Document("$replaceRoot", new Document("newRoot", "$notice")));
        if (from != null && until != null) {
            stages.add(new Document("$match", new Document("createdAt", new Document("$gte", Date.from(from)).append("$lt", Date.from(until)))));
        }
        stages.add(new Document("$sort", new Document("createdAt", -1).append("_id", -1)));
        stages.add(new Document("$facet", new Document("items", List.of(new Document("$skip", (long) page * size), new Document("$limit", size)))
                .append("totals", List.of(new Document("$count", "count")))));
        var operations = stages.stream().map(stage -> (AggregationOperation) context -> stage).toList();
        var result = mongo.aggregate(Aggregation.newAggregation(operations), "notifications", Document.class).getUniqueMappedResult();
        if (result == null) return new Page(List.of(), 0);
        var items = result.getList("items", Document.class).stream().map(row -> mongo.getConverter().read(Notification.class, row)).toList();
        var totals = result.getList("totals", Document.class);
        return new Page(items, totals.isEmpty() ? 0 : ((Number) totals.getFirst().get("count")).longValue());
    }
}
