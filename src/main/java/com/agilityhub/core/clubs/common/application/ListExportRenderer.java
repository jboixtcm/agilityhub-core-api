package com.agilityhub.core.clubs.common.application;

import java.awt.Color;
import java.io.*;
import java.util.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Component;

/** Text cells prevent spreadsheet formula execution. PDF wraps every value onto continuation lines. */
@Component
public class ListExportRenderer {
    public byte[] render(String format, String club, String color, String listKey, List<String> columns, List<Map<String, Object>> rows) {
        try { return format.equals("xlsx") ? xlsx(listKey, columns, rows) : pdf(club, color, listKey, columns, rows); }
        catch (IOException ex) { throw new UncheckedIOException("Could not render list export", ex); }
    }
    private byte[] xlsx(String key, List<String> columns, List<Map<String, Object>> rows) throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(key);
            var style = workbook.createCellStyle(); var font = workbook.createFont(); font.setBold(true); style.setFont(font);
            var header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) { var cell = header.createCell(c); cell.setCellValue(columns.get(c)); cell.setCellStyle(style); sheet.setColumnWidth(c, 35 * 256); }
            int number = 1;
            for (var row : rows) {
                var target = sheet.createRow(number++);
                for (int c = 0; c < columns.size(); c++) { target.createCell(c).setCellValue(text(row.get(columns.get(c)))); }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, rows.size(), 0, columns.size() - 1));
            workbook.write(output); return output.toByteArray();
        }
    }
    private byte[] pdf(String club, String color, String key, List<String> columns, List<Map<String, Object>> rows) throws IOException {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
             var fontInput = ListExportRenderer.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDFont font = PDType0Font.load(document, Objects.requireNonNull(fontInput, "PDFBox bundled Liberation Sans"));
            var lines = new ArrayList<String>();
            int number = 0;
            for (var row : rows) {
                lines.add("#" + ++number);
                for (String column : columns) {
                    String value = printable(font, column + ": " + text(row.get(column)));
                    lines.addAll(wrap(font, value, 9, 515));
                }
                lines.add("");
            }
            if (lines.isEmpty()) { lines.add("0"); }
            int offset = 0;
            while (offset < lines.size()) {
                var page = new PDPage(PDRectangle.A4); document.addPage(page);
                try (var stream = new PDPageContentStream(document, page)) {
                    stream.setNonStrokingColor(Color.decode(color)); stream.addRect(32, 784, 531, 30); stream.fill();
                    stream.beginText(); stream.setNonStrokingColor(Color.WHITE); stream.setFont(font, 12); stream.newLineAtOffset(40, 795);
                    stream.showText(printable(font, club + " · " + key)); stream.endText();
                    stream.beginText(); stream.setNonStrokingColor(Color.BLACK); stream.setFont(font, 9); stream.setLeading(12); stream.newLineAtOffset(40, 764);
                    int end = Math.min(lines.size(), offset + 58);
                    for (; offset < end; offset++) { stream.showText(lines.get(offset)); stream.newLine(); }
                    stream.endText();
                    stream.beginText(); stream.setFont(font, 8); stream.newLineAtOffset(40, 35); stream.showText(Integer.toString(document.getNumberOfPages())); stream.endText();
                }
            }
            document.save(output); return output.toByteArray();
        }
    }
    private static List<String> wrap(PDFont font, String value, float size, float width) throws IOException {
        var lines = new ArrayList<String>(); var line = new StringBuilder();
        for (int code : value.codePoints().toArray()) {
            String next = new String(Character.toChars(code));
            if (font.getStringWidth(line + next) * size / 1000 > width) {
                int space = line.lastIndexOf(" ");
                if (space > 0) { lines.add(line.substring(0, space)); line.delete(0, space + 1); }
                else { lines.add(line.toString()); line.setLength(0); }
            }
            line.append(next);
        }
        lines.add(line.toString()); return lines;
    }
    private static String printable(PDFont font, String value) {
        var text = new StringBuilder();
        value.codePoints().forEach(code -> {
            String item = Character.isISOControl(code) ? " " : new String(Character.toChars(code));
            try { font.encode(item); text.append(item); } catch (IllegalArgumentException | IOException unsupported) { text.append('?'); }
        });
        return text.toString();
    }
    private static String text(Object value) {
        if (value == null) { return ""; }
        if (value instanceof Map<?, ?> map) { return String.join("; ", map.entrySet().stream().map(e -> e.getKey() + ": " + text(e.getValue())).toList()); }
        if (value instanceof List<?> list) { return String.join("; ", list.stream().map(ListExportRenderer::text).toList()); }
        return value.toString();
    }
}
