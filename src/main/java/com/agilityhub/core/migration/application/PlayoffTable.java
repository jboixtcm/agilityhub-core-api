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
                    var sheet = book.getSheetAt(0); var result = new ArrayList<List<String>>();
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
    public static List<List<String>> csv(String text) {
        var rows = new ArrayList<List<String>>(); var fields = new ArrayList<String>(); var value = new StringBuilder();
        boolean quoted = false, closed = false;
        char delimiter = text.lines().findFirst().orElse("").contains(";") ? ';' : ',';
        for (int i = text.startsWith("\uFEFF") ? 1 : 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { value.append('"'); i++; }
                    else { quoted = false; closed = true; }
                } else { value.append(ch); }
            } else if (ch == delimiter || ch == '\n' || ch == '\r') {
                fields.add(value.toString()); value.setLength(0); closed = false;
                if (ch != delimiter) {
                    rows.add(List.copyOf(fields)); fields.clear();
                    if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') { i++; }
                }
            } else if (ch == '"' && value.isEmpty() && !closed) { quoted = true; }
            else {
                if (closed || ch == '"') { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
                value.append(ch);
            }
        }
        if (quoted) { throw new ApiException(ErrorCode.INPUT_SCHEMA_MISMATCH); }
        if (!fields.isEmpty() || !value.isEmpty() || closed) { fields.add(value.toString()); rows.add(List.copyOf(fields)); }
        return rows;
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
