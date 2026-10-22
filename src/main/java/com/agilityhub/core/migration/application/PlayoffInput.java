package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.*;
import java.util.*;

public record PlayoffInput(Map<String,List<Row>> files, List<MigrationReport.Entry> incidents) {
    /**
     * E11-T15 round 2 (the kit's review 2, N6; ruling E156; S18 R-18-11b): the auxiliary files, where one row is one receipt, pack,
     * forecast line, mandate or «paid until» — a short, long or broken row is `WARNING INPUT_SCHEMA_MISMATCH` on its line and is
     * skipped. The census files keep one row = one person: a broken row there is an `ERROR` (the load would guess a person).
     */
    public static final Set<String> ROW_TOLERANT = Set.of("receipts", "packs", "forecast", "mandates", "paidUntil");
    public record Row(String file, int row, Map<String,String> values) {
        public String get(String field) { return values.getOrDefault(field, "").strip(); }
    }
    public static PlayoffInput read(Path dir, MappingConfig mapping) {
        var data = new LinkedHashMap<String,List<Row>>(); var incidents = new ArrayList<MigrationReport.Entry>();
        for (var entry : new TreeMap<>(mapping.files()).entrySet()) {
            String key = entry.getKey(); var schema = entry.getValue(); var file = dir.resolve(schema.name()); var rows = new ArrayList<Row>(); data.put(key, rows);
            if (!Files.isRegularFile(file)) {
                if (schema.required()) { incidents.add(new MigrationReport.Entry(key,0,key,"ERROR","INPUT_SCHEMA_MISMATCH")); }
                continue;
            }
            boolean tolerant = ROW_TOLERANT.contains(key);
            List<List<String>> table;
            try { table = tolerant ? PlayoffTable.readRows(file) : PlayoffTable.read(file); }
            catch (ApiException invalid) { incidents.add(new MigrationReport.Entry(key,0,key,"ERROR",invalid.code().name())); continue; }
            // The header decides the file: an empty or broken one is the file's error.
            if (table.isEmpty() || table.getFirst() == null) { incidents.add(new MigrationReport.Entry(key,1,key,"ERROR","INPUT_SCHEMA_MISMATCH")); continue; }
            var header = table.getFirst(); boolean valid = true;
            for (var column : schema.columns()) {
                if (column.at() > header.size() || !column.header().equals(header.get(column.at()-1))) {
                    incidents.add(new MigrationReport.Entry(key,1,key,"ERROR","INPUT_SCHEMA_MISMATCH",column.field()+"@"+column.at())); valid = false;
                }
            }
            if (header.size() > schema.columns().size()) { incidents.add(new MigrationReport.Entry(key,1,key,"WARNING","INPUT_SCHEMA_MISMATCH","extraColumnsFrom@"+(schema.columns().size()+1))); }
            if (!valid) { continue; }
            for (int i = 1; i < table.size(); i++) {
                var raw = table.get(i);
                if (raw == null || raw.size() != header.size()) { incidents.add(new MigrationReport.Entry(key,i+1,key,tolerant ? "WARNING" : "ERROR","INPUT_SCHEMA_MISMATCH")); continue; }
                var fields = new LinkedHashMap<String,String>();
                for (var column : schema.columns()) { fields.put(column.field(), raw.get(column.at()-1)); }
                rows.add(new Row(key,i+1,fields));
            }
        }
        return new PlayoffInput(data, incidents);
    }
}
