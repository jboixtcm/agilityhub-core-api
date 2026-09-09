package com.agilityhub.core.identity.application;

import java.util.List;
import java.util.TreeMap;

/** Only record ordinals, counts and fixed reasons can leave the importer. */
public record LearnImportReport(boolean dryRun, long created, long linked, long skipped, long errors, List<Entry> rows) {
    public LearnImportReport { rows = List.copyOf(rows); }
    static LearnImportReport of(boolean dryRun, List<Entry> rows) {
        return new LearnImportReport(dryRun, count(rows, Outcome.CREATED), count(rows, Outcome.LINKED),
                count(rows, Outcome.SKIPPED), count(rows, Outcome.ERROR), rows);
    }
    private static long count(List<Entry> rows, Outcome outcome) { return rows.stream().filter(row -> row.outcome() == outcome).count(); }
    public String render() {
        var reasons = new TreeMap<Reason, Integer>();
        rows.forEach(row -> reasons.merge(row.reason(), 1, Integer::sum));
        return (dryRun ? "Dry run: " : "Imported: ") + created + (dryRun ? " would create, " : " created, ")
                + linked + (dryRun ? " would link, " : " linked, ") + skipped + " skipped, " + errors + " errors\nReasons: " + reasons;
    }
    public enum Outcome { CREATED, LINKED, SKIPPED, ERROR }
    public enum Reason { NEW_ACCOUNT, PASSWORD_ADOPTED, PASSWORD_PRESERVED, ALREADY_IMPORTED, PLATFORM_ADMIN_GRANTED,
        GUEST, INVALID_COLUMNS, INVALID_ID, INVALID_EMAIL, INVALID_PASSWORD, INVALID_NAME, INVALID_ROLE,
        INVALID_CREATED_AT, INVALID_CSV, INPUT_UNREADABLE, LEARN_ID_CONFLICT, EMAIL_CONFLICT, ACCOUNT_NOT_ACTIVE }
    public record Entry(int row, Outcome outcome, Reason reason) { }
}
