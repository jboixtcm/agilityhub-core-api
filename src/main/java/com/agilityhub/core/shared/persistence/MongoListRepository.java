package com.agilityhub.core.shared.persistence;

import com.agilityhub.core.shared.application.contract.ApiContracts.*;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.util.*;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

@Repository
public class MongoListRepository extends TenantRepository<MongoListRepository.ListRecord> {
    public record ListRecord(String id, String clubId) implements TenantEntity { }
    public MongoListRepository(MongoTemplate mongo) { super(mongo, ListRecord.class); }

    public ListPage<Map<String, Object>> page(ListDataset data, ListQuery query) {
        var pipeline = pipeline(data, query);
        var items = new ArrayList<Document>();
        items.add(new Document("$sort", sort(data, query)));
        items.add(new Document("$skip", (long) query.page() * query.size()));
        items.add(new Document("$limit", query.size()));
        items.add(project(data, query.fields()));
        pipeline.add(new Document("$facet", new Document("items", items)
                .append("total", List.of(new Document("$count", "count")))));
        var result = aggregate(data, pipeline).getFirst();
        var totals = result.getList("total", Document.class);
        long total = totals.isEmpty() ? 0 : ((Number) totals.getFirst().get("count")).longValue();
        var rows = result.getList("items", Document.class).stream().map(row -> publicRow(data, row)).toList();
        return new ListPage<>(rows, query.page(), query.size(), total,
                (int) Math.min(Integer.MAX_VALUE, (total + query.size() - 1) / query.size()), query.filters());
    }
    public List<Map<String, Object>> rows(ListDataset data, ListQuery query, long skip, int limit, List<String> fields) {
        var pipeline = pipeline(data, query);
        pipeline.add(new Document("$sort", sort(data, query)));
        pipeline.add(new Document("$skip", skip));
        pipeline.add(new Document("$limit", limit));
        pipeline.add(project(data, fields));
        return aggregate(data, pipeline).stream().map(row -> publicRow(data, row)).toList();
    }
    public long exportCount(ListDataset data, ListQuery query, int limit) {
        var stages = pipeline(data, query);
        stages.add(new Document("$limit", limit));
        stages.add(new Document("$count", "count"));
        var result = aggregate(data, stages);
        return result.isEmpty() ? 0 : ((Number) result.getFirst().get("count")).longValue();
    }
    /** The cursor owns its server resources and must be closed by the renderer's caller. */
    public java.util.stream.Stream<Map<String, Object>> exportStream(ListDataset data, ListQuery query, List<String> fields, int limit) {
        var stages = pipeline(data, query);
        stages.add(new Document("$sort", sort(data, query)));
        stages.add(new Document("$limit", limit));
        stages.add(project(data, fields));
        var cursor = mongo.getCollection(data.collection()).aggregate(stages).allowDiskUse(true)
                .maxTime(5, TimeUnit.MINUTES).batchSize(200).iterator();
        return java.util.stream.StreamSupport.stream(java.util.Spliterators.spliteratorUnknownSize(cursor, Spliterator.ORDERED), false)
                .map(row -> publicRow(data, row)).onClose(cursor::close);
    }
    public List<FilterValue> facets(ListDataset data, ListQuery query, String field) {
        var pipeline = pipeline(data, query);
        String path = "$" + data.definition().field(field).path();
        pipeline.add(new Document("$project", new Document("values", path)));
        pipeline.add(new Document("$unwind", "$values"));
        pipeline.add(new Document("$match", new Document("values", new Document("$ne", null))));
        // Count documents, not duplicated elements in an array (e.g. two dogs at one level).
        pipeline.add(new Document("$group", new Document("_id", new Document("id", "$_id").append("value", "$values"))));
        pipeline.add(new Document("$group", new Document("_id", "$_id.value").append("count", new Document("$sum", 1))));
        pipeline.add(new Document("$sort", new Document("count", -1).append("_id", 1)));
        pipeline.add(new Document("$limit", 50));
        return aggregate(data, pipeline).stream().map(row -> {
            Object value = normalize(row.get("_id"), Set.of(), "");
            return new FilterValue(value, data.label().apply(field, value), ((Number) row.get("count")).longValue());
        }).toList();
    }
    private List<Document> pipeline(ListDataset data, ListQuery query) {
        var stages = new ArrayList<Document>();
        stages.add(new Document("$match", tenantQuery().getQueryObject()));
        stages.addAll(data.stages());
        var predicates = new ArrayList<Criteria>();
        for (Filter filter : query.filters()) { predicates.add(criteria(data.definition(), filter)); }
        if (!query.q().isEmpty()) {
            predicates.add(new Criteria().orOperator(data.definition().searchable().stream()
                    .map(path -> Criteria.where(path).regex(Pattern.quote(query.q()), "i")).toList()));
        }
        if (!predicates.isEmpty()) { stages.add(new Document("$match", new Criteria().andOperator(predicates).getCriteriaObject())); }
        return stages;
    }
    private Criteria criteria(ListDefinition definition, Filter filter) {
        var field = definition.field(filter.field());
        var criterion = Criteria.where(field.path());
        Object value = mongoValue(field.type(), filter.value());
        return switch (filter.op()) {
            case eq -> criterion.is(value);
            case ne -> criterion.ne(value);
            case in -> criterion.in((List<?>) value);
            case nin -> criterion.nin((List<?>) value);
            case lt -> criterion.lt(value);
            case lte -> criterion.lte(value);
            case gt -> criterion.gt(value);
            case gte -> criterion.gte(value);
            case exists -> criterion.exists((Boolean) value);
            case contains -> criterion.regex(Pattern.quote((String) value), "i");
            case startsWith -> criterion.regex("^" + Pattern.quote((String) value), "i");
            case between -> criterion.gte(((List<?>) value).get(0)).lte(((List<?>) value).get(1));
        };
    }
    private Object mongoValue(ListDefinition.Type type, Object value) {
        if (value instanceof List<?> values) { return values.stream().map(v -> mongoValue(type, v)).toList(); }
        if (value instanceof Boolean) { return value; }
        if (type == ListDefinition.Type.INSTANT) { return Date.from(java.time.Instant.parse(value.toString())); }
        if (type == ListDefinition.Type.NUMBER) { return new org.bson.types.Decimal128((java.math.BigDecimal) value); }
        return value;
    }
    private Document sort(ListDataset data, ListQuery query) {
        var sort = new Document();
        for (String term : query.sort()) {
            var parts = term.split(",");
            sort.put(data.definition().sorts().get(parts[0]), parts[1].equals("desc") ? -1 : 1);
        }
        sort.putIfAbsent("_id", 1);
        return sort;
    }
    private Document project(ListDataset data, List<String> fields) {
        var projection = new Document("_id", 0).append("id", "$_id");
        data.projection().forEach((name, expression) -> {
            if (fields.isEmpty() || fields.contains(name)) { projection.put(name, expression); }
        });
        return new Document("$project", projection);
    }
    private List<Document> aggregate(ListDataset data, List<Document> pipeline) {
        return mongo.execute(data.collection(), collection -> collection.aggregate(pipeline)
                .allowDiskUse(true).maxTime(30, TimeUnit.SECONDS).into(new ArrayList<>()));
    }
    @SuppressWarnings("unchecked")
    private Map<String, Object> publicRow(ListDataset data, Document document) { return (Map<String, Object>) normalize(document, data.nullablePaths(), ""); }
    private Object normalize(Object value, Set<String> nullable, String path) {
        if (value instanceof Date date) { return date.toInstant().toString(); }
        if (value instanceof Map<?, ?> map) {
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                String next = path.isEmpty() ? key.toString() : path + "." + key;
                if (item != null) { copy.put(key.toString(), normalize(item, nullable, next)); }
                else if (nullable.contains(next)) { copy.put(key.toString(), com.fasterxml.jackson.databind.node.NullNode.instance); }
            });
            return copy;
        }
        if (value instanceof List<?> list) { return list.stream().map(item -> normalize(item, nullable, path + "[]")).toList(); }
        return value;
    }
}
