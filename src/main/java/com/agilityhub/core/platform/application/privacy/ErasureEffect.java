package com.agilityhub.core.platform.application.privacy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What an erasure step did: the files left to delete (`store:key`, S14 §3 `filesPending[]`) and the documents it changed per collection. */
public record ErasureEffect(List<String> files, Map<String, Long> documents) {
    public static final ErasureEffect NONE = new ErasureEffect(List.of(), Map.of());
    public ErasureEffect { files = List.copyOf(files); documents = Map.copyOf(documents); }
    public static Builder builder() { return new Builder(); }
    public ErasureEffect plus(ErasureEffect other) {
        var builder = builder(); builder.files.addAll(files); builder.files.addAll(other.files);
        builder.documents.putAll(documents); other.documents.forEach(builder::count);
        return builder.build();
    }
    public static final class Builder {
        private final List<String> files = new ArrayList<>();
        private final Map<String, Long> documents = new LinkedHashMap<>();
        public Builder file(String store, String key) { if (key != null && !key.isBlank()) { files.add(store + ":" + key); } return this; }
        public Builder count(String collection, long changed) { if (changed > 0) { documents.merge(collection, changed, Long::sum); } return this; }
        public ErasureEffect build() { return new ErasureEffect(files, documents); }
    }
}
