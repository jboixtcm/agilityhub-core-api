package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.shared.application.lists.*;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Component;

/** Tenant-qualified joins and an allowlisted projection for the universal weeks list. */
@Component
public class WeekLists implements ListProvider {
    public Set<String> keys() { return Set.of("weeks"); }
    public ListDataset dataset(String key) {
        var fields = List.of("id", "isoYear", "isoWeek", "startDate", "endDate", "state", "generatedAt", "validatedAt", "weekdayTemplateName", "saturdayTemplateName", "classCounts");
        var definition = new ListDefinition("weeks", Map.of("id", new ListDefinition.Field("_id", ListDefinition.Type.TEXT),
                "startDate", new ListDefinition.Field("startDate", ListDefinition.Type.DATE), "state", new ListDefinition.Field("state", ListDefinition.Type.TEXT)),
                Map.of("startDate", "startDate"), List.of(), fields, fields, List.of("startDate,desc"), Set.copyOf(fields));
        var stages = new ArrayList<Document>();
        stages.add(new Document("$set", new Document("startDate", date("$startDate")).append("endDate", date("$endDate"))));
        stages.add(lookup("week_templates", "weekdayTemplateId", "_id", "weekday"));
        stages.add(lookup("week_templates", "saturdayTemplateId", "_id", "saturday"));
        stages.add(lookup("class_sessions", "_id", "weekId", "sessions"));
        stages.add(new Document("$set", new Document("weekdayTemplateName", new Document("$arrayElemAt", List.of("$weekday.name", 0)))
                .append("saturdayTemplateName", new Document("$arrayElemAt", List.of("$saturday.name", 0)))
                .append("classCounts", new Document("draft", count("DRAFT")).append("active", count("ACTIVE")).append("cancelled", count("CANCELLED")))));
        var projection = new LinkedHashMap<String, Object>(); fields.forEach(f -> projection.put(f, 1)); projection.put("id", "$_id"); projection.put("_id", 0);
        return new ListDataset(definition, "weeks", stages, projection, Set.of("generatedAt", "validatedAt", "weekdayTemplateName", "saturdayTemplateName"), (field, value) -> Objects.toString(value, ""));
    }
    private Document date(String field) {
        return new Document("$dateToString", new Document("date", new Document("$convert", new Document("input", field)
                .append("to", "date").append("onError", null).append("onNull", null))).append("format", "%Y-%m-%d").append("timezone", "UTC"));
    }
    private Document lookup(String collection, String local, String foreign, String alias) {
        var equality = new Document("$and", List.of(new Document("$eq", List.of("$clubId", "$$club")),
                new Document("$eq", List.of("$" + foreign, "$$ref"))));
        return new Document("$lookup", new Document("from", collection)
                .append("let", new Document("club", "$clubId").append("ref", "$" + local))
                .append("pipeline", List.of(new Document("$match", new Document("$expr", equality)))).append("as", alias));
    }
    private Document count(String state) {
        return new Document("$size", new Document("$filter", new Document("input", "$sessions").append("as", "item")
                .append("cond", new Document("$eq", List.of("$$item.state", state)))));
    }
}
