package com.agilityhub.core.shared.application.lists;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.bson.Document;

/**
 * A context supplies tenant-scoped joins and an explicit safe output projection. `leadingSort` orders the rows before the
 * requested sort (S10 R-10-13: D14's unread rows first, then `activityAt`); empty for every other list.
 */
public record ListDataset(ListDefinition definition, String collection, List<Document> stages,
        Map<String, Object> projection, java.util.Set<String> nullablePaths, BiFunction<String, Object, String> label,
        java.util.function.UnaryOperator<Map<String, Object>> sanitize, Document leadingSort) {
    public ListDataset {
        leadingSort = leadingSort == null ? new Document() : leadingSort;
    }
    public ListDataset(ListDefinition definition, String collection, List<Document> stages,
            Map<String, Object> projection, java.util.Set<String> nullablePaths, BiFunction<String, Object, String> label,
            java.util.function.UnaryOperator<Map<String, Object>> sanitize) {
        this(definition, collection, stages, projection, nullablePaths, label, sanitize, new Document());
    }
    public ListDataset(ListDefinition definition, String collection, List<Document> stages,
            Map<String, Object> projection, java.util.Set<String> nullablePaths, BiFunction<String, Object, String> label) {
        this(definition, collection, stages, projection, nullablePaths, label, java.util.function.UnaryOperator.identity());
    }
}
