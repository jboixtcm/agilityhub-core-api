package com.agilityhub.core.shared.application.lists;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import org.bson.Document;

/** A context supplies tenant-scoped joins and an explicit safe output projection. */
public record ListDataset(ListDefinition definition, String collection, List<Document> stages,
        Map<String, Object> projection, java.util.Set<String> nullablePaths, BiFunction<String, Object, String> label) { }
