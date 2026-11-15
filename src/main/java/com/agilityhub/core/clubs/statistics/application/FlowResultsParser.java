package com.agilityhub.core.clubs.statistics.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionValidation.*;

/** Reviewed interchange CSV. This is deliberately not presented as an undocumented provider API. */
@Component
public class FlowResultsParser {
    public static final String HEADER = "license,dogName,trialId,runId,date,trial,grade,category,discipline,position,faults,refusals,totalPenalty,timeSeconds,speedMps,qualification,point";
    public Parsed parse(String content, String sourceUrl) {
        content(content); String url = url(Source.FLOW_AGILITY, sourceUrl);
        var rows = csv(content.replaceFirst("^\\uFEFF", ""));
        if (rows.size() < 2 || !rows.getFirst().equals(List.of(HEADER.split(",")))) { throw invalid("INVALID_FLOW_CSV_HEADER"); }
        var entries = new ArrayList<Entry>(); var keys = new HashSet<String>(); var years = new HashSet<String>();
        for (var row : rows.subList(1, rows.size())) {
            if (row.size() != 17) { throw invalid("INVALID_FLOW_CSV_ROW"); }
            for (var field : row) { if (field.length() > 300) { throw invalid("FIELD_TOO_LONG"); } }
            if (row.get(0).isBlank() || row.get(0).length() > 30 || row.get(1).isBlank()
                    || !row.get(2).matches("[A-Za-z0-9._-]{1,80}") || !row.get(3).matches("[A-Za-z0-9._-]{1,80}")
                    || row.get(5).isBlank() || row.get(6).isBlank() || row.get(7).isBlank()) { throw invalid("MISSING_RESULT_IDENTITY"); }
            try {
                LocalDate date = LocalDate.parse(row.get(4));
                if (date.getYear() < 2000 || date.getYear() > 2100) { throw invalid("INVALID_RESULT_DATE"); }
                String year = Integer.toString(date.getYear()); years.add(year);
                var discipline = Discipline.valueOf(row.get(8)); var point = Point.valueOf(row.get(16));
                String key = row.get(2) + ":" + row.get(3) + ":" + row.get(0);
                if (!keys.add(key)) { throw invalid("DUPLICATE_RESULT"); }
                BigDecimal time = number(row.get(13)), speed = number(row.get(14));
                if ((time != null && time.signum() == 0) || (speed != null && speed.compareTo(BigDecimal.valueOf(20)) > 0)) {
                    throw invalid("INVALID_NUMBER");
                }
                entries.add(new Entry(key, row.get(0), row.get(1), "", "", year, null, row.get(7),
                        row.get(9).isBlank() ? null : FcagStandingsParser.rank(row.get(9)), null,
                        row.get(2), row.get(3), date, row.get(5), row.get(6), discipline,
                        integer(row.get(10)), integer(row.get(11)), number(row.get(12)), time, speed, row.get(15), point));
            } catch (IllegalArgumentException | java.time.format.DateTimeParseException failure) { throw invalid("INVALID_FLOW_CSV_VALUE"); }
        }
        if (years.size() != 1) { throw invalid("ONE_YEAR_PER_IMPORT"); }
        return new Parsed(Source.FLOW_AGILITY, url, years.iterator().next(), List.copyOf(entries));
    }
    private static BigDecimal number(String value) { return value.isBlank() ? null : FcagStandingsParser.decimal(value); }
    private static Integer integer(String value) {
        if (value.isBlank()) { return null; }
        try { int n = Integer.parseInt(value); if (n >= 0 && n <= 100) { return n; } }
        catch (NumberFormatException ignored) { }
        throw invalid("INVALID_NUMBER");
    }
    /** RFC 4180 quoting, CRLF, embedded commas/newlines; rejects malformed/truncated quoting. */
    static List<List<String>> csv(String text) {
        var rows = new ArrayList<List<String>>(); var row = new ArrayList<String>(); var cell = new StringBuilder();
        boolean quoted = false, closed = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { cell.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else { cell.append(c); }
            } else if (c == ',' || c == '\n' || c == '\r') {
                row.add(cell.toString().trim()); cell.setLength(0); closed = false;
                if (c != ',') {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; }
                    if (!(row.size() == 1 && row.getFirst().isBlank())) { rows.add(List.copyOf(row)); }
                    row.clear();
                }
            } else if (c == '"' && cell.isEmpty() && !closed) { quoted = true; }
            else {
                if (closed || c == '"') { throw invalid("MALFORMED_CSV"); }
                cell.append(c);
            }
            if (rows.size() > MAX_ROWS + 1) { throw invalid("TOO_MANY_ROWS"); }
        }
        if (quoted) { throw invalid("MALFORMED_CSV"); }
        if (!row.isEmpty() || !cell.isEmpty() || closed) { row.add(cell.toString().trim()); rows.add(List.copyOf(row)); }
        if (rows.size() > MAX_ROWS + 1) { throw invalid("TOO_MANY_ROWS"); }
        return rows;
    }
}
