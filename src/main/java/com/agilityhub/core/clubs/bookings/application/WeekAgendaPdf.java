package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.shared.application.IcuMessageSource;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.*;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.stereotype.Component;

/**
 * S10 R-10-15 `GET /instructor/week/export?format=pdf`: the {@link WeekAgendaQuery} result drawn as a landscape A4 grid
 * (days × start times), synchronous, the only synchronous PDF of the product so far (not an `ExportJob`: no S3, no
 * rate limit). It follows the list exports' look (`clubs.common` `ListExportRenderer`, which this context cannot import:
 * `clubs.common` already depends on `clubs.bookings`): PDFBox's Liberation Sans, a band in the club's theme colour with
 * the club's name, and the footer «Pàgina {n} de {m} · {club}». Labels come from `export.column.instructor-week.*` in
 * the caller's locale; dates and times are the club-local values of the query.
 */
@Component
public class WeekAgendaPdf {
    static final String KEYS = "export.column.instructor-week.";
    private static final float MARGIN = 32, HEADER = 64, FOOTER = 48, HOUR_WIDTH = 44, LINE = 9.5f, SIZE = 7.5f;
    private final IcuMessageSource messages;
    public WeekAgendaPdf(IcuMessageSource messages) { this.messages = messages; }

    public byte[] render(Map<String, Object> week, String clubName, String color, Locale locale) {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream();
             var fontInput = WeekAgendaPdf.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            PDFont font = PDType0Font.load(document, Objects.requireNonNull(fontInput, "PDFBox bundled Liberation Sans"));
            var size = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
            @SuppressWarnings("unchecked") var range = (Map<String, Object>) week.get("week");
            var start = (LocalDate) range.get("startDate"); var end = (LocalDate) range.get("endDate");
            var days = new ArrayList<LocalDate>(); for (var d = start; !d.isAfter(end); d = d.plusDays(1)) { days.add(d); }
            @SuppressWarnings("unchecked") var rows = (List<String>) week.get("rows");
            @SuppressWarnings("unchecked") var cells = (List<Map<String, Object>>) week.get("cells");
            var dates = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale);
            String title = text("title", Map.of(), locale) + " · " + text("range", Map.of("start", start.format(dates), "end", end.format(dates)), locale);
            float columnWidth = (size.getWidth() - 2 * MARGIN - HOUR_WIDTH) / days.size();
            var pages = new Pages(document, font, size, clubName, color == null ? "#333333" : color, title);
            pages.headers(days.stream().map(d -> text("day." + d.getDayOfWeek().name(), Map.of(), locale) + " " + d.getDayOfMonth() + "/" + d.getMonthValue()).toList(),
                    text("hour", Map.of(), locale), columnWidth);
            for (String time : rows.isEmpty() ? List.<String>of() : rows) {
                var columns = new ArrayList<List<String>>();
                for (var day : days) {
                    var lines = new ArrayList<String>();
                    for (var cell : cells) {
                        if (!day.equals(cell.get("date")) || !time.equals(cell.get("time"))) { continue; }
                        for (String line : wrap(font, label(cell, locale), columnWidth - 6)) { lines.add(line); }
                    }
                    columns.add(lines);
                }
                pages.row(time, columns, columnWidth);
            }
            if (rows.isEmpty()) { pages.row("", List.of(List.of(text("empty", Map.of(), locale))), size.getWidth() - 2 * MARGIN - HOUR_WIDTH); }
            pages.close();
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                String footer = messages.format("export.pdf.page", Map.of("page", page + 1, "total", document.getNumberOfPages()), locale) + " · " + clubName;
                try (var stream = new PDPageContentStream(document, document.getPage(page), PDPageContentStream.AppendMode.APPEND, true, true)) {
                    stream.beginText(); stream.setFont(font, 8); stream.newLineAtOffset(MARGIN, 24); stream.showText(printable(font, footer)); stream.endText();
                }
            }
            document.save(output);
            return output.toByteArray();
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    /** «{descripció} n/n {pista} · {instructor}» (+ «· {n} espera»), «Reserva {pista} — {guia} + {gos}», «Bloqueig {pista} — {motiu}». */
    String label(Map<String, Object> cell, Locale locale) {
        String ring = Objects.toString(cell.get("ringName"), "");
        String range = cell.get("time") + "–" + cell.get("endTime");
        return switch (cell.get("kind").toString()) {
            case "CLASS" -> {
                var values = new HashMap<String, Object>(); values.put("description", Objects.toString(cell.get("displayDescription"), ""));
                values.put("booked", cell.get("booked")); values.put("capacity", cell.get("capacity")); values.put("ring", ring);
                values.put("instructor", Objects.toString(cell.get("instructorName"), ""));
                String text = text("class", values, locale);
                if (cell.get("waiting") instanceof Integer waiting && waiting > 0) { text += " " + text("waiting", Map.of("waiting", waiting), locale); }
                if ("CANCELLED".equals(Objects.toString(cell.get("state")))) { text += " (" + text("cancelled", Map.of(), locale) + ")"; }
                yield text;
            }
            case "TRAINING" -> range + " " + text("training", Map.of("ring", ring, "who", Objects.toString(cell.get("who"), "")), locale);
            default -> {
                String reason = text("reason." + cell.get("reason"), Map.of(), locale);
                String note = cell.get("note") == null ? "" : " (" + cell.get("note") + ")";
                yield range + " " + text("block", Map.of("ring", ring, "reason", reason), locale) + note;
            }
        };
    }
    private String text(String key, Map<String, Object> values, Locale locale) { return messages.format(KEYS + key, values, locale); }

    private static final class Pages {
        private final PDDocument document; private final PDFont font; private final PDRectangle size; private final String club, color, title;
        private PDPageContentStream stream; private float y; private List<String> headers; private String hourLabel; private float columnWidth;
        Pages(PDDocument document, PDFont font, PDRectangle size, String club, String color, String title) {
            this.document = document; this.font = font; this.size = size; this.club = club; this.color = color; this.title = title;
        }
        void headers(List<String> headers, String hourLabel, float columnWidth) throws IOException {
            this.headers = headers; this.hourLabel = hourLabel; this.columnWidth = columnWidth; page();
        }
        private void page() throws IOException {
            close();
            var page = new PDPage(size); document.addPage(page); stream = new PDPageContentStream(document, page);
            stream.setNonStrokingColor(Color.decode(color)); stream.addRect(MARGIN, size.getHeight() - 58, size.getWidth() - 2 * MARGIN, 30); stream.fill();
            stream.beginText(); stream.setNonStrokingColor(Color.WHITE); stream.setFont(font, 12); stream.newLineAtOffset(MARGIN + 8, size.getHeight() - 47);
            stream.showText(printable(font, club + " · " + title)); stream.endText();
            stream.setNonStrokingColor(Color.BLACK);
            y = size.getHeight() - HEADER - 12;
            stream.beginText(); stream.setFont(font, 8); stream.newLineAtOffset(MARGIN, y); stream.showText(printable(font, hourLabel)); stream.endText();
            for (int i = 0; i < headers.size(); i++) {
                stream.beginText(); stream.setFont(font, 8); stream.newLineAtOffset(MARGIN + HOUR_WIDTH + i * columnWidth + 3, y);
                stream.showText(printable(font, headers.get(i))); stream.endText();
            }
            y -= 4; line(y); y -= LINE;
        }
        void row(String time, List<List<String>> columns, float width) throws IOException {
            int height = Math.max(1, columns.stream().mapToInt(List::size).max().orElse(1));
            if (y - height * LINE < FOOTER) { page(); }
            stream.beginText(); stream.setFont(font, SIZE); stream.newLineAtOffset(MARGIN, y); stream.showText(printable(font, time)); stream.endText();
            for (int c = 0; c < columns.size(); c++) {
                float x = MARGIN + HOUR_WIDTH + c * width + 3, lineY = y;
                for (String text : columns.get(c)) {
                    stream.beginText(); stream.setFont(font, SIZE); stream.newLineAtOffset(x, lineY); stream.showText(printable(font, text)); stream.endText();
                    lineY -= LINE;
                }
            }
            y -= height * LINE; line(y + LINE - 3); y -= 3;
        }
        /** The thin line between start times, as on the mockup. */
        private void line(float at) throws IOException {
            stream.setStrokingColor(new Color(0xCC, 0xCC, 0xCC)); stream.setLineWidth(0.4f);
            stream.moveTo(MARGIN, at); stream.lineTo(size.getWidth() - MARGIN, at); stream.stroke();
        }
        void close() throws IOException { if (stream != null) { stream.close(); stream = null; } }
    }
    private static List<String> wrap(PDFont font, String value, float width) throws IOException {
        var lines = new ArrayList<String>(); var line = new StringBuilder();
        for (int code : printable(font, value).codePoints().toArray()) {
            String next = new String(Character.toChars(code));
            if (font.getStringWidth(line + next) * SIZE / 1000 > width) {
                int space = line.lastIndexOf(" ");
                if (space > 0) { lines.add(line.substring(0, space)); line.delete(0, space + 1); }
                else { lines.add(line.toString()); line.setLength(0); }
            }
            line.append(next);
        }
        if (!line.isEmpty()) { lines.add(line.toString()); }
        return lines;
    }
    private static String printable(PDFont font, String value) {
        var text = new StringBuilder();
        value.codePoints().forEach(code -> {
            String item = Character.isISOControl(code) ? " " : new String(Character.toChars(code));
            try { font.encode(item); text.append(item); } catch (IllegalArgumentException | IOException unsupported) { text.append('?'); }
        });
        return text.toString();
    }
}
