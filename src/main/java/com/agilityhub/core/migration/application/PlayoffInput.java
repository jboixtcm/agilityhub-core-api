package com.agilityhub.core.migration.application;

import com.agilityhub.core.migration.domain.*;
import com.agilityhub.core.shared.domain.*;
import java.nio.file.*;
import java.util.*;

public record PlayoffInput(Map<String,List<Row>> files, List<MigrationReport.Entry> incidents) {
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
            List<List<String>> table;
            try { table = PlayoffTable.read(file); }
            catch (ApiException invalid) { incidents.add(new MigrationReport.Entry(key,0,key,"ERROR",invalid.code().name())); continue; }
            if (table.isEmpty()) { incidents.add(new MigrationReport.Entry(key,1,key,"ERROR","INPUT_SCHEMA_MISMATCH")); continue; }
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
                if (raw.size() != header.size()) { incidents.add(new MigrationReport.Entry(key,i+1,key,"ERROR","INPUT_SCHEMA_MISMATCH")); continue; }
                var fields = new LinkedHashMap<String,String>();
                for (var column : schema.columns()) { fields.put(column.field(), raw.get(column.at()-1)); }
                rows.add(new Row(key,i+1,fields));
            }
        }
        return new PlayoffInput(data, incidents);
    }
}
