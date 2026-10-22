package com.agilityhub.core.migration.application;

import com.agilityhub.core.shared.domain.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.apache.poi.ss.usermodel.*;

/** CSV and first-sheet XLSX reader. Formulas are never evaluated. */
public final class PlayoffTable {
    private PlayoffTable() { }
    public static List<List<String>> read(Path file) {
        try {
            if (file.toString().endsWith(".xlsx")) {
                try (var stream = Files.newInputStream(file); var book = WorkbookFactory.create(stream)) {
                    if (book.getNumberOfSheets()==0) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                    var sheet = book.getSheetAt(0); var result = new ArrayList<List<String>>();
                    if (sheet.getRow(0)==null || sheet.getRow(0).getLastCellNum()<1) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                    var format = new DataFormatter(Locale.ROOT); int width = sheet.getRow(0).getLastCellNum();
                    for (var row : sheet) {
                        var values = new ArrayList<String>();
                        for (int i = 0; i < Math.max(width, row.getLastCellNum()); i++) {
                            var cell = row.getCell(i);
                            if (cell != null && cell.getCellType() == CellType.FORMULA) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                            values.add(format.formatCellValue(cell));
                        }
                        result.add(values);
                    }
                    return result;
                }
            }
            return csv(Files.readString(file));
        } catch (IOException | IllegalArgumentException failure) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
    }
    /** The file's rows; a stray quote anywhere refuses the whole file (`INPUT_SCHEMA_MISMATCH`). */
    public static List<List<String>> csv(String text) { return parse(text, false); }
    /**
     * E11-T15 round 2 (S18 R-18-11b): the same rows, but a row a stray quote breaks (a quote inside an unquoted value, text after
     * a closing quote, a quote never closed) comes back as {@code null} and the reading goes on at the next line, so one bad row
     * of an auxiliary file never takes the file with it. The caller reports the null rows.
     * <p>E11-T21 (ruling E162): here a quoted field never spans lines, so one record is one line. A quote still open at the end of
     * its line breaks that line only; the next line starts clean, and its opening quote never closes the broken one.
     */
    public static List<List<String>> csvRows(String text) { return parse(text, true); }
    /** {@link #read}, with {@link #csvRows}'s per-row recovery for a CSV file. */
    public static List<List<String>> readRows(Path file) {
        if (file.toString().endsWith(".xlsx")) { return read(file); }
        try { return csvRows(Files.readString(file)); }
        catch (IOException | IllegalArgumentException failure) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
    }
    private static List<List<String>> parse(String text, boolean lenient) {
        var rows = new ArrayList<List<String>>(); var fields = new ArrayList<String>(); var value = new StringBuilder();
        boolean quoted = false, closed = false;
        char delimiter = text.lines().findFirst().orElse("").contains(";") ? ';' : ',';
        int i = text.startsWith("\uFEFF") ? 1 : 0;
        while (true) {
            if (i >= text.length()) {
                if (!quoted) { break; }
                if (!lenient) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                // A quote never closed on the last line: that line is broken.
                rows.add(null); fields.clear(); value.setLength(0); closed = false;
                break;
            }
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { value.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else if (lenient && (ch == '\n' || ch == '\r')) {
                    // A quote still open at the end of its line: that line is broken and the next one is read with a clean state.
                    rows.add(null); fields.clear(); value.setLength(0); quoted = false; closed = false; i = nextLine(text, i);
                    continue;
                } else { value.append(ch); }
            } else if (ch == delimiter || ch == '\n' || ch == '\r') {
                fields.add(value.toString()); value.setLength(0); closed = false;
                if (ch != delimiter) {
                    rows.add(List.copyOf(fields)); fields.clear();
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; }
                }
            } else if (ch == '"' && value.isEmpty() && !closed) { quoted = true; }
            else if (closed || ch == '"') {
                if (!lenient) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                rows.add(null); fields.clear(); value.setLength(0); closed = false; i = nextLine(text, i);
                continue;
            }
            else { value.append(ch); }
            i++;
        }
        if (!fields.isEmpty() || !value.isEmpty() || closed) { fields.add(value.toString()); rows.add(List.copyOf(fields)); }
        return rows;
    }
    /** The index just after the line break that ends the line holding {@code at} (the text's length on the last line). */
    private static int nextLine(String text, int at) {
        int i = at;
        while (i < text.length() && text.charAt(i) != '\n' && text.charAt(i) != '\r') { i++; }
        if (i < text.length() && text.charAt(i) == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; }
        return Math.min(text.length(), i + 1);
    }
    public static void write(Path file, List<List<String>> rows) throws IOException {
        if (file.toString().endsWith(".xlsx")) {
            try (var book = new org.apache.poi.xssf.usermodel.XSSFWorkbook(); var stream = Files.newOutputStream(file)) {
                var sheet = book.createSheet("Export");
                for (int r = 0; r < rows.size(); r++) { var row = sheet.createRow(r);
                    for (int c = 0; c < rows.get(r).size(); c++) { row.createCell(c).setCellValue(rows.get(r).get(c)); }
                }
                book.write(stream);
            }
        } else {
            var text = new StringBuilder();
            for (var row : rows) { text.append(row.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"").collect(java.util.stream.Collectors.joining(","))).append('\n'); }
            Files.writeString(file, text);
        }
    }
}
