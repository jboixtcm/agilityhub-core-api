package com.agilityhub.core.migration.domain;

import java.util.*;

/** Reports contain only adapter keys, row ordinals, outcomes and fixed incident codes. */
public record MigrationReport(boolean dryRun, List<Entry> rows) {
    /** R-18-14: a re-execution transition that is rejected, not reconciled; only its record is left untouched. */
    public static final String REEXECUTION_UNSUPPORTED = "REEXECUTION_UNSUPPORTED";
    public MigrationReport { rows = List.copyOf(rows); }
    public record Entry(String file, int row, String entity, String outcome, String code, String field) {
        public Entry(String file, int row, String entity, String outcome, String code) { this(file,row,entity,outcome,code,""); }
    }
    public long count(String entity, String outcome) { return rows.stream().filter(r -> r.entity().equals(entity) && r.outcome().equals(outcome)).count(); }
    public boolean hasErrors() { return rows.stream().anyMatch(r -> r.outcome().equals("ERROR")); }
    /** Errors that stop the whole apply; an unsupported re-execution does not (R-18-14). */
    public boolean hasBlockingErrors() { return rows.stream().anyMatch(r -> r.outcome().equals("ERROR") && !r.code().equals(REEXECUTION_UNSUPPORTED)); }
    public long unsupported() { return rows.stream().filter(r -> r.outcome().equals("ERROR") && r.code().equals(REEXECUTION_UNSUPPORTED)).count(); }
    public String render() {
        var out = new StringBuilder(dryRun ? "Playoff DRY_RUN\n" : "Playoff APPLY\n");
        // R-18-15: a blocked run says only that; a partial line tells a proposal (dry run) from what the apply did.
        if (hasBlockingErrors()) { out.append("Validation failed; no changes applied. Counts below describe the proposed changes.\n"); }
        else if (unsupported() > 0) {
            out.append(REEXECUTION_UNSUPPORTED).append(": ").append(unsupported())
                .append(dryRun ? " records would be left untouched; the rest would be applied" : " records were left untouched; the rest was applied")
                .append(" (R-18-14). On staging, the way out is --reset and a new load.\n");
        }
        for (String entity : List.of("members", "dogs", "familyGroups", "accounts")) {
            out.append(entity).append(": created=").append(count(entity,"CREATED")).append(" updated=").append(count(entity,"UPDATED"))
                .append(" skipped=").append(count(entity,"SKIPPED")).append(" errors=").append(count(entity,"ERROR"));
            // R-18-12: family groups proposed to the club for records that share an email; never created by the import.
            if (entity.equals("familyGroups")) { out.append(" proposed=").append(count(entity,"PROPOSED")); }
            out.append('\n');
        }
        var incidents = new TreeMap<String,Integer>();
        rows.stream().filter(r -> !r.code().isEmpty() && !r.outcome().equals("PROPOSED")).forEach(r -> incidents.merge(r.code(),1,Integer::sum));
        out.append("Incidents: ").append(incidents).append('\n');
        for (var row : rows) { out.append(row.file()).append(':').append(row.row()).append(' ').append(row.entity()).append(' ').append(row.outcome()).append(' ').append(row.code()).append(row.field().isEmpty() ? "" : " field="+row.field()).append('\n'); }
        return out.toString();
    }
}
