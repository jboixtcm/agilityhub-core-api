package com.agilityhub.core.clubs.common.application;

import java.awt.Color;
import java.io.*;
import java.util.*;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.time.ZoneId;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.*;
import org.springframework.stereotype.Component;

/** Text cells prevent spreadsheet formula execution. PDF wraps every value onto continuation lines. */
@Component
public class ListExportRenderer {
    private final IcuMessageSource messages;
    public ListExportRenderer() {
        try { messages = new IcuMessageSource(); } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    public byte[] render(String format, String club, String color, String listKey, List<String> columns, List<Map<String, Object>> rows) {
        try (var output = new ByteArrayOutputStream()) {
            write(format, club, color, listKey, columns, columns, rows, output, null);
            return output.toByteArray();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    public void renderTo(String format, String club, String color, String listKey, List<String> columns, Iterable<Map<String, Object>> rows,
            Locale locale, ZoneId zone, OutputStream output) {
        var values = new ExportValues(messages, locale, zone);
        Iterable<Map<String, Object>> formatted = () -> java.util.stream.StreamSupport.stream(rows.spliterator(), false).map(row -> {
            var cells = new LinkedHashMap<String, Object>(); columns.forEach(column -> cells.put(column, values.text(row.get(column)))); return (Map<String, Object>) cells;
        }).iterator();
        try { write(format, club, color, listKey, columns, columns.stream().map(column -> values.label(listKey, column)).toList(), formatted, output, locale); }
        catch (IOException ex) { throw new UncheckedIOException(ex); }
    }
    private void write(String format, String club, String color, String listKey, List<String> columns, List<String> labels,
            Iterable<Map<String, Object>> rows, OutputStream output, Locale locale) throws IOException {
        if (format.equals("xlsx")) { xlsx(listKey, columns, labels, rows, output); }
        else { pdf(club, color, listKey, columns, labels, rows, output, locale); }
    }
    private void xlsx(String key, List<String> columns, List<String> labels, Iterable<Map<String, Object>> rows, OutputStream output) throws IOException {
        try (var workbook = new SXSSFWorkbook(100)) {
            workbook.setCompressTempFiles(true);
            var sheet = workbook.createSheet(key);
            var style = workbook.createCellStyle(); var font = workbook.createFont(); font.setBold(true); style.setFont(font);
            var header = sheet.createRow(0);
            for (int c = 0; c < columns.size(); c++) { var cell = header.createCell(c); cell.setCellValue(labels.get(c)); cell.setCellStyle(style); sheet.setColumnWidth(c, 35 * 256); }
            int number = 1;
            for (var row : rows) {
                var target = sheet.createRow(number++);
                for (int c = 0; c < columns.size(); c++) { target.createCell(c).setCellValue(text(row.get(columns.get(c)))); }
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, number - 1, 0, columns.size() - 1));
            workbook.write(output);
        }
    }
    private void pdf(String club, String color, String key, List<String> columns, List<String> labels,
            Iterable<Map<String, Object>> rows, OutputStream output, Locale locale) throws IOException {
        try (var document = new PDDocument(org.apache.pdfbox.io.IOUtils.createTempFileOnlyStreamCache());
             var fontInput = ListExportRenderer.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDFont font = PDType0Font.load(document, Objects.requireNonNull(fontInput, "PDFBox bundled Liberation Sans"));
            var pageSize = columns.size() > 6 ? new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth()) : PDRectangle.A4;
            try (var pages = new PdfPages(document, font, pageSize, club, color, key)) {
                int number = 0;
                for (var row : rows) {
                    pages.line("#" + ++number);
                    for (int c = 0; c < columns.size(); c++) {
                        String value = printable(font, labels.get(c) + ": " + text(row.get(columns.get(c))));
                        for (String line : wrap(font, value, 9, pageSize.getWidth() - 80)) { pages.line(line); }
                    }
                    pages.line("");
                }
                if (number == 0) { pages.line("0"); }
            }
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                String label = locale == null ? Integer.toString(page + 1) : messages.format("export.pdf.page", Map.of("page", page + 1, "total", document.getNumberOfPages()), locale);
                try (var stream = new PDPageContentStream(document, document.getPage(page), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText(); stream.setFont(font, 8); stream.newLineAtOffset(40, 35); stream.showText(printable(font, label + " · " + club)); stream.endText();
                }
            }
            document.save(output);
        }
    }
    private static final class PdfPages implements AutoCloseable {
        private final PDDocument document; private final PDFont font; private final PDRectangle size; private final String club, color, key;
        private PDPageContentStream stream; private float y;
        PdfPages(PDDocument document, PDFont font, PDRectangle size, String club, String color, String key) {
            this.document = document; this.font = font; this.size = size; this.club = club; this.color = color; this.key = key;
        }
        void line(String text) throws IOException {
            if (stream == null || y < 60) {
                close();
                var page = new PDPage(size); document.addPage(page); stream = new PDPageContentStream(document, page);
                stream.setNonStrokingColor(Color.decode(color)); stream.addRect(32, size.getHeight() - 58, size.getWidth() - 64, 30); stream.fill();
                stream.beginText(); stream.setNonStrokingColor(Color.WHITE); stream.setFont(font, 12); stream.newLineAtOffset(40, size.getHeight() - 47);
                stream.showText(printable(font, club + " · " + key)); stream.endText();
                stream.setNonStrokingColor(Color.BLACK); y = size.getHeight() - 78;
            }
            stream.beginText(); stream.setFont(font, 9); stream.newLineAtOffset(40, y); stream.showText(text); stream.endText(); y -= 12;
        }
        @Override public void close() throws IOException { if (stream != null) { stream.close(); stream = null; } }
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
