package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.application.OfferUsage;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

/** Tenant-scoped projections until census and billing own the final reference adapters. */
@Repository
public class MongoOfferUsage extends TenantRepository<MongoOfferUsage.Reference> implements OfferUsage {
    public record Reference(String id, String clubId) implements TenantEntity { }
    public MongoOfferUsage(MongoTemplate mongo) { super(mongo, Reference.class); }
    private List<Document> rows(String collection) { return mongo.find(tenantQuery(), Document.class, collection); }
    private boolean matches(Map<?, ?> row, String plan, Set<String> prices) {
        return plan.equals(row.get("planId")) || prices.contains(row.get("priceId"));
    }
    @Override public Map<String, Long> plan(String id) {
        Set<String> prices = new HashSet<>();
        mongo.find(tenantQuery().addCriteria(Criteria.where("planId").is(id)), Document.class, "prices")
                .forEach(price -> prices.add(price.getString("_id")));
        Map<String, Long> counts = new LinkedHashMap<>();
        for (var entry : Map.of("members", "members", "packBalances", "pack_balances", "invoiceLines", "invoice_lines", "upfrontCollections", "collections").entrySet()) {
            counts.put(entry.getKey(), rows(entry.getValue()).stream().filter(row -> matches(row, id, prices)).count());
        }
        for (Document invoice : rows("invoices")) {
            for (Object raw : invoice.getList("lines", Object.class, List.of())) {
                if (raw instanceof Map<?, ?> line && matches(line, id, prices)) { counts.merge("invoiceLines", 1L, Long::sum); }
            }
        }
        return counts;
    }
    @Override public PriceUsage price(String id) {
        List<Map<?, ?>> lines = new ArrayList<>(mongo.find(tenantQuery().addCriteria(Criteria.where("priceId").is(id)), Document.class, "invoice_lines"));
        for (Document invoice : mongo.find(tenantQuery().addCriteria(Criteria.where("lines.priceId").is(id)), Document.class, "invoices")) {
            for (Object raw : invoice.getList("lines", Object.class, List.of())) {
                if (raw instanceof Map<?, ?> line && id.equals(line.get("priceId"))) {
                    Map<String, Object> merged = new HashMap<>(invoice);
                    line.forEach((key, value) -> merged.put(key.toString(), value)); lines.add(merged);
                }
            }
        }
        LocalDate last = null; boolean known = true;
        for (var line : lines) {
            Object raw = line.get("periodTo");
            if (raw == null) { raw = line.get("period"); }
            LocalDate end = periodEnd(raw);
            if (end == null) { known = false; }
            else if (last == null || end.isAfter(last)) { last = end; }
        }
        return new PriceUsage(!lines.isEmpty(), last, known);
    }
    private LocalDate periodEnd(Object value) {
        if (value instanceof Map<?, ?> period) { return periodEnd(period.get("to")); }
        if (value instanceof Date date) { return date.toInstant().atZone(ZoneOffset.UTC).toLocalDate(); }
        if (value == null) { return null; }
        try {
            String text = value.toString();
            return text.length() == 7 ? YearMonth.parse(text).atEndOfMonth() : LocalDate.parse(text);
        } catch (java.time.format.DateTimeParseException invalid) { return null; }
    }
}
