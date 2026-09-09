package com.agilityhub.core.migration.domain;

import java.util.*;

/** Reports contain only adapter keys, row ordinals, outcomes and fixed incident codes. */
public record MigrationReport(boolean dryRun, List<Entry> rows) {
    public MigrationReport { rows = List.copyOf(rows); }
    public record Entry(String file, int row, String entity, String outcome, String code, String field) {
        public Entry(String file, int row, String entity, String outcome, String code) { this(file,row,entity,outcome,code,""); }
    }
    public long count(String entity, String outcome) { return rows.stream().filter(r -> r.entity().equals(entity) && r.outcome().equals(outcome)).count(); }
    public boolean hasErrors() { return rows.stream().anyMatch(r -> r.outcome().equals("ERROR")); }
    public String render() {
        var out = new StringBuilder(dryRun ? "Playoff DRY_RUN\n" : "Playoff APPLY\n");
        if (hasErrors()) { out.append("Validation failed; no changes applied. Counts below describe the proposed changes.\n"); }
        for (String entity : List.of("members", "dogs", "familyGroups", "accounts")) {
            out.append(entity).append(": created=").append(count(entity,"CREATED")).append(" updated=").append(count(entity,"UPDATED"))
                .append(" skipped=").append(count(entity,"SKIPPED")).append(" errors=").append(count(entity,"ERROR")).append('\n');
        }
        var incidents = new TreeMap<String,Integer>(); rows.stream().filter(r -> !r.code().isEmpty()).forEach(r -> incidents.merge(r.code(),1,Integer::sum));
        out.append("Incidents: ").append(incidents).append('\n');
        for (var row : rows) { out.append(row.file()).append(':').append(row.row()).append(' ').append(row.entity()).append(' ').append(row.outcome()).append(' ').append(row.code()).append(row.field().isEmpty() ? "" : " field="+row.field()).append('\n'); }
        return out.toString();
    }
}
