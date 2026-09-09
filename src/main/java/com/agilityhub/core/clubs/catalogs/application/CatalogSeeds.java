package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.CatalogKind;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import com.agilityhub.core.platform.application.ClubCatalogProvisioner;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class CatalogSeeds implements ClubCatalogProvisioner {
    private final CatalogService catalogs;
    private final PlanService plans;
    private final PriceService prices;
    private final CatalogSeedRepository references;
    private final ObjectMapper mapper;
    public CatalogSeeds(CatalogService catalogs, PlanService plans, PriceService prices, CatalogSeedRepository references, ObjectMapper mapper) {
        this.catalogs = catalogs; this.plans = plans; this.prices = prices; this.references = references; this.mapper = mapper;
    }
    private String reference(String code) { return TenantContext.require() + ":faq:" + code; }
    private CatalogKind kind(String section) {
        return switch (section) { case "levels" -> CatalogKind.LEVEL; case "rings" -> CatalogKind.RING; case "faq" -> CatalogKind.FAQ; default -> throw new IllegalArgumentException(section); };
    }
    private Object existing(String section, Map<String, Object> fields) {
        return switch (section) {
            case "plans" -> plans.list(true).stream().filter(p -> p.code().equals(fields.get("code"))).findFirst().orElse(null);
            case "prices" -> plans.list(true).stream().filter(p -> p.code().equals(fields.get("planCode"))).findFirst()
                    .flatMap(p -> prices.list(p.id(), null).stream().filter(price -> price.concept().name().equals(fields.get("concept"))
                            && price.validFrom().toString().equals(fields.get("validFrom"))).findFirst()).orElse(null);
            case "faq" -> references.findById(reference((String) fields.get("code")))
                    .flatMap(ref -> catalogs.list(CatalogKind.FAQ, true).stream().filter(f -> f.id().equals(ref.entityId())).findFirst()).orElseGet(() -> catalogs.list(CatalogKind.FAQ, true).stream()
                            .filter(item -> item.id().equals(fields.get("code"))).findFirst().orElse(null));
            default -> catalogs.list(kind(section), true).stream().filter(item -> item instanceof Level level
                    ? level.code().equals(fields.get("code")) : ((Ring) item).shortName().equals(fields.get("shortName"))).findFirst().orElse(null);
        };
    }
    @Override public List<Change> plan(JsonNode input) {
        var changes = new ArrayList<Change>();
        for (String section : List.of("levels", "rings", "plans", "prices", "faq")) {
            var keys = new HashSet<String>();
            for (var entry : input.path(section)) {
                Map<String, Object> fields = mapper.convertValue(entry, new TypeReference<>() { });
                String key = section.equals("prices") ? entry.path("planCode").asText() + ":" + entry.path("concept").asText() + ":" + entry.path("validFrom").asText()
                        : entry.path(section.equals("rings") ? "shortName" : "code").asText();
                if (!keys.add(key)) { throw new ApiException(ErrorCode.DUPLICATE_NAME, Map.of("field", "catalogs." + section)); }
                JsonNode old = mapper.valueToTree(existing(section, fields));
                var desired = entry.deepCopy();
                ((com.fasterxml.jackson.databind.node.ObjectNode) desired).remove("planCode");
                if (section.equals("faq")) { ((com.fasterxml.jackson.databind.node.ObjectNode) desired).remove("code"); }
                boolean changed = old.isNull() || !matches(desired, old);
                if (changed) { changes.add(new Change(section, key, old.path("id").asText(null), fields)); }
            }
        }
        return List.copyOf(changes);
    }
    private boolean matches(JsonNode desired, JsonNode old) {
        if (desired.isNull()) { return old.isNull() || old.isMissingNode(); }
        if (desired.isNumber() && old.isNumber()) { return desired.decimalValue().compareTo(old.decimalValue()) == 0; }
        if (desired.isObject()) {
            var fields = desired.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!matches(field.getValue(), old.path(field.getKey()))) { return false; }
            }
            return true;
        }
        return desired.equals(old);
    }
    @Override public void provision(List<Change> changes) {
        for (var change : changes) {
            var fields = new LinkedHashMap<>(change.fields());
            String id = change.id();
            switch (change.section()) {
                case "plans" -> {
                    if (id == null) { plans.create(fields); }
                    else { fields.put("version", plans.get(id).version()); plans.update(id, fields); }
                }
                case "prices" -> {
                    String code = (String) fields.remove("planCode");
                    fields.put("planId", plans.list(true).stream().filter(p -> p.code().equals(code)).findFirst()
                            .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)).id());
                    if (id == null) { prices.seed(fields); }
                    else { fields.put("version", prices.get(id).version()); prices.update(id, fields); }
                }
                default -> {
                    if (change.section().equals("faq")) { fields.remove("code"); }
                    if (id == null) {
                        id = (String) catalogs.create(kind(change.section()), fields).get("id");
                        if (change.section().equals("faq")) { references.deleteById(reference(change.key())); references.insert(new CatalogSeedReference(reference(change.key()), TenantContext.require(), id)); }
                    } else { fields.put("version", catalogs.get(kind(change.section()), id).version()); catalogs.update(kind(change.section()), id, fields); }
                }
            }
        }
    }
    private Map<String, Object> fields(Object item) {
        Map<String, Object> result = mapper.convertValue(item, new TypeReference<>() { });
        result.keySet().removeAll(Set.of("id", "clubId", "version", "createdAt", "updatedAt", "createdByAccountId", "updatedByAccountId"));
        result.computeIfPresent("taxPercent", (key, value) -> ((Number) value).doubleValue());
        removeNulls(result);
        return result;
    }
    private void removeNulls(Map<?, ?> values) {
        values.values().removeIf(Objects::isNull);
        for (Object value : values.values()) { if (value instanceof Map<?, ?> nested) { removeNulls(nested); } }
    }
    @Override public Map<String, Object> export() {
        var result = new LinkedHashMap<String, Object>();
        for (String section : List.of("levels", "rings", "faq")) {
            result.put(section, catalogs.list(kind(section), true).stream().map(item -> {
                var data = fields(item);
                if (section.equals("faq")) {
                    String prefix = TenantContext.require() + ":faq:";
                    data.put("code", references.findAll().stream().filter(ref -> ref.entityId().equals(item.id()))
                            .map(ref -> ref.id().substring(prefix.length())).findFirst().orElse(item.id()));
                }
                return data;
            }).toList());
        }
        result.put("plans", plans.list(true).stream().map(this::fields).toList());
        var allPrices = new ArrayList<Map<String, Object>>();
        if (plans.config().modules().contains(com.agilityhub.core.platform.application.Module.BILLING)) {
            for (var plan : plans.list(true)) { for (var price : prices.list(plan.id(), null)) {
                var data = fields(price); data.remove("planId"); data.put("planCode", plan.code()); allPrices.add(data);
            } }
        }
        result.put("prices", allPrices); result.put("instructors", List.of()); return result;
    }
}
