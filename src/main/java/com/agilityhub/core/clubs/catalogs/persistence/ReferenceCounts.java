package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import java.util.*;
import java.util.function.UnaryOperator;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

/** E11-T10 (INC-38): the per-id reference counts of a catalog list in a fixed number of reads (D11's levels, D16, D17). */
final class ReferenceCounts {
    private ReferenceCounts() { }

    /** Per id, the current club's documents whose {@code field} (a value or an array) holds it, in one aggregate (zero when none). */
    static Map<String, Long> countBy(MongoTemplate mongo, String collection, String field, Collection<String> ids, UnaryOperator<Criteria> filter) {
        var counts = new HashMap<String, Long>(); ids.forEach(id -> counts.put(id, 0L));
        if (ids.isEmpty()) { return counts; }
        var values = new ArrayList<>(ids);
        var pipeline = Aggregation.newAggregation(Aggregation.match(filter.apply(Criteria.where("clubId").is(TenantContext.require()).and(field).in(values))),
                context -> new Document("$project", new Document("keys", new Document("$setIntersection", List.of(
                        new Document("$cond", List.of(new Document("$isArray", "$" + field), "$" + field, List.of("$" + field))), values)))),
                context -> new Document("$unwind", "$keys"),
                context -> new Document("$group", new Document("_id", "$keys").append("count", new Document("$sum", 1))));
        for (var row : mongo.aggregate(pipeline, collection, Document.class)) { counts.put(row.getString("_id"), ((Number) row.get("count")).longValue()); }
        return counts;
    }

    /** Per id, its classes in the current club's week templates ({@code tenant}), read once. */
    static Map<String, Long> templateClasses(MongoTemplate mongo, Query tenant, String field, Collection<String> ids) {
        var counts = new HashMap<String, Long>(); ids.forEach(id -> counts.put(id, 0L));
        if (ids.isEmpty()) { return counts; }
        for (var template : mongo.find(tenant.addCriteria(Criteria.where("classes." + field).in(ids)), Document.class, "week_templates")) {
            for (var item : template.getList("classes", Document.class)) {
                for (String id : ids) {
                    if (item.get(field) instanceof List<?> values ? values.contains(id) : id.equals(item.get(field))) { counts.merge(id, 1L, Long::sum); }
                }
            }
        }
        return counts;
    }
}
