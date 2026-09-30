package com.agilityhub.core.shared.application.lists;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.bson.Document;

/**
 * A context supplies tenant-scoped joins and an explicit safe output projection. `leadingSort` orders the rows before the
 * requested sort (S10 R-10-13: D14's unread rows first, then `activityAt`); empty for every other list. `exportProjection`
 * replaces the projection of a column in the list's exports only (S03 R-03-24: the dogs' «Titular» cell is the owner's full
 * name, while the list sends the owner object); empty for every other list. `search` is the list's own filter for a non-blank
 * `q`, in place of the case-insensitive match of the definition's searchable paths: D14 searches names another context holds
 * (S10 §6, E6-T06); null for every other list.
 */
public record ListDataset(ListDefinition definition, String collection, List<Document> stages,
        Map<String, Object> projection, java.util.Set<String> nullablePaths, BiFunction<String, Object, String> label,
        java.util.function.UnaryOperator<Map<String, Object>> sanitize, Document leadingSort, Map<String, Object> exportProjection,
        Function<String, Document> search) {
    public ListDataset {
        leadingSort = leadingSort == null ? new Document() : leadingSort;
        exportProjection = exportProjection == null ? Map.of() : Map.copyOf(exportProjection);
    }
    public ListDataset(ListDefinition definition, String collection, List<Document> stages,
            Map<String, Object> projection, java.util.Set<String> nullablePaths, BiFunction<String, Object, String> label,
            java.util.function.UnaryOperator<Map<String, Object>> sanitize, Document leadingSort, Map<String, Object> exportProjection) {
        this(definition, collection, stages, projection, nullablePaths, label, sanitize, leadingSort, exportProjection, null);
    }
    public ListDataset(ListDefinition definition, String collection, List<Document> stages,
            Map<String, Object> projection, java.util.Set<String> nullablePaths, BiFunction<String, Object, String> label,
            java.util.function.UnaryOperator<Map<String, Object>> sanitize, Document leadingSort) {
        this(definition, collection, stages, projection, nullablePaths, label, sanitize, leadingSort, Map.of());
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
    /** The same dataset whose exports project each column of {@code columns} with its expression instead of the list's. */
    public ListDataset withExportProjection(Map<String, Object> columns) {
        return new ListDataset(definition, collection, stages, projection, nullablePaths, label, sanitize, leadingSort, columns, search);
    }
    /** The same dataset whose non-blank `q` selects the rows {@code filter} returns for it (a `$match` document), after its stages. */
    public ListDataset withSearch(Function<String, Document> filter) {
        return new ListDataset(definition, collection, stages, projection, nullablePaths, label, sanitize, leadingSort, exportProjection, filter);
    }
}
