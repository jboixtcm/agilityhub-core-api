package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.scheduling.application.ports.AttendanceStatusPort;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link WeekAgendaPdf} (S10 R-10-15; T-10-20). The PDF is read back with PDFBox, every content
 * stream of every page: each `Tj` with the text state in force (font size, `Td` position, reset at `BT`), the fill and
 * stroke colours, the band's rectangle, the separators' `m`/`l`/`S`, line widths and the `BT`/`ET` balance. This pins the
 * band, title, headers, rows, multi-line cells, separators, the page break above the footer, the footer, the empty week,
 * the cell wrapping at the column width minus 6 pt (a long word is cut, never repeated) and the cell labels. Labels come
 * from the real catalan bundle.
 */
class WeekAgendaPdfSurvivorsTest {
    static final Locale CA = Locale.forLanguageTag("ca");
    static final String CLUB = "Club Agility Example";
    /** Landscape A4 as WeekAgendaPdf builds it. */
    static final float W = PDRectangle.A4.getHeight(), H = PDRectangle.A4.getWidth();
    /** Monday to Saturday: six columns after the 44 pt hour column, 32 pt margins. */
    static final float COLUMN = (W - 2 * 32 - 44) / 6;
    static final float HEAD_Y = H - 64 - 12, FIRST_ROW = HEAD_Y - 4 - 9.5f;
    static final LocalDate MONDAY = LocalDate.parse("2026-10-05"), SATURDAY = LocalDate.parse("2026-10-10");

    final WeekAgendaPdf pdf;
    WeekAgendaPdfSurvivorsTest() throws IOException { pdf = new WeekAgendaPdf(new IcuMessageSource()); }

    // --- the grid: band, title, headers, cells, separators, footer -----------------------------------------------------------

    @Test void T_10_20_theGridDrawsTheBandHeadersCellsAndSeparatorsAtTheirPlaces() {
        // Monday 18:00 one class; Tuesday 18:00 two classes on two rings (a two-line cell); Wednesday 19:00 one class.
        var cells = List.of(
                classCell("class-a", MONDAY, "18:00", "Grup A", 4, "Pista 1", "Neus"),
                classCell("class-b", MONDAY.plusDays(1), "18:00", "Grup B", 5, "Pista 1", "Marc"),
                classCell("class-c", MONDAY.plusDays(1), "18:00", "Grup C", 3, "Pista 2", "Neus"),
                classCell("class-d", MONDAY.plusDays(2), "19:00", "Grup D", 2, "Pista 1", "Neus"));
        for (var cell : cells) { assertThat(width(pdf.label(cell, CA))).as("fixture: one line per class").isLessThan(COLUMN - 6); }

        var pages = read(pdf.render(week(List.of("18:00", "19:00"), cells), CLUB, "#1E6091", CA));

        assertThat(pages).hasSize(1);
        var page = pages.getFirst();
        // The band in the club's colour, then the title in white, then everything else in black.
        assertThat(page.rects).containsExactly(box(32, H - 58, W - 64, 30));
        assertThat(page.fills).isEqualTo(1);
        assertThat(page.fillColors).containsExactly(rgb(0x1E / 255f, 0x60 / 255f, 0x91 / 255f), rgb(1, 1, 1), rgb(0, 0, 0));
        var title = page.texts.getFirst();
        assertThat(title.text()).startsWith(CLUB + " · Agenda de la setmana");
        assertThat(describe(title.withText(""))).isEqualTo(describe(new Text("", 40, H - 47, 12)));
        float second = FIRST_ROW - 2 * 9.5f - 3;
        assertThat(page.texts.subList(1, page.texts.size()).stream().map(WeekAgendaPdfSurvivorsTest::describe).toList()).containsExactly(
                describe(new Text("Hora", 32, HEAD_Y, 8)),
                describe(new Text("dl 5/10", 79, HEAD_Y, 8)),
                describe(new Text("dt 6/10", 79 + COLUMN, HEAD_Y, 8)),
                describe(new Text("dc 7/10", 79 + 2 * COLUMN, HEAD_Y, 8)),
                describe(new Text("dj 8/10", 79 + 3 * COLUMN, HEAD_Y, 8)),
                describe(new Text("dv 9/10", 79 + 4 * COLUMN, HEAD_Y, 8)),
                describe(new Text("ds 10/10", 79 + 5 * COLUMN, HEAD_Y, 8)),
                describe(new Text("18:00", 32, FIRST_ROW, 7.5f)),
                describe(new Text("Grup A 4/6 Pista 1 · Neus", 79, FIRST_ROW, 7.5f)),
                describe(new Text("Grup B 5/6 Pista 1 · Marc", 79 + COLUMN, FIRST_ROW, 7.5f)),
                describe(new Text("Grup C 3/6 Pista 2 · Neus", 79 + COLUMN, FIRST_ROW - 9.5f, 7.5f)),
                describe(new Text("19:00", 32, second, 7.5f)),
                describe(new Text("Grup D 2/6 Pista 1 · Neus", 79 + 2 * COLUMN, second, 7.5f)),
                describe(new Text("Pàgina 1 de 1 · " + CLUB, 32, 24, 8)));
        // One thin grey separator under the headers and one under each start time.
        var separators = List.of(HEAD_Y - 4, FIRST_ROW - 12.5f, second - 3);
        assertThat(page.moves).containsExactlyElementsOf(separators.stream().map(y -> point(32, y)).toList());
        assertThat(page.lines).containsExactlyElementsOf(separators.stream().map(y -> point(W - 32, y)).toList());
        assertThat(page.strokes).isEqualTo(3);
        assertThat(page.strokeColors).containsExactly(rgb(0.8f, 0.8f, 0.8f), rgb(0.8f, 0.8f, 0.8f), rgb(0.8f, 0.8f, 0.8f));
        assertThat(page.widths).containsExactly(0.4f, 0.4f, 0.4f);
        assertThat(page.begins).isEqualTo(page.ends).isEqualTo(page.texts.size());
    }

    @Test void T_10_20_aWeekLongerThanThePageBreaksAboveTheFooterAndRepeatsTheHeaders() {
        // 40 start times (08:00 … 17:45), one class each: 36 single-line rows fit above the 48 pt footer.
        var rows = new ArrayList<String>(); var cells = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < 40; i++) {
            String time = LocalTime.of(8, 0).plusMinutes(15L * i).toString();
            rows.add(time);
            cells.add(classCell("class-" + i, MONDAY.plusDays(i % 6), time, "Grup A", 4, "Pista 1", "Neus"));
        }

        var pages = read(pdf.render(week(rows, cells), CLUB, "#1E6091", CA));

        assertThat(pages).hasSize(2);
        assertThat(times(pages.get(0))).isEqualTo(rows.subList(0, 36));
        assertThat(times(pages.get(1))).isEqualTo(rows.subList(36, 40));
        var first = pages.get(1).texts.stream().filter(t -> t.text().equals("17:00")).findFirst().orElseThrow();
        assertThat(describe(first)).isEqualTo(describe(new Text("17:00", 32, FIRST_ROW, 7.5f)));
        assertThat(pages.get(1).texts).extracting(Text::text).contains("Hora", "dl 5/10", "Pàgina 2 de 2 · " + CLUB);
        assertThat(pages.get(0).texts).extracting(Text::text).contains("Pàgina 1 de 2 · " + CLUB);
        // The last row of page 1 stays one full line above the footer.
        assertThat(pages.get(0).texts.stream().filter(t -> t.size() == 7.5f).mapToDouble(Text::y).min().orElseThrow()).isGreaterThanOrEqualTo(48 + 9.5 - 0.01);
    }

    @Test void T_10_20_anEmptyWeekSaysSoInTheFirstColumn() {
        var page = read(pdf.render(week(List.of(), List.of()), CLUB, "#1E6091", CA)).getFirst();

        assertThat(page.texts.stream().map(WeekAgendaPdfSurvivorsTest::describe).toList())
                .contains(describe(new Text("Cap classe aquesta setmana", 79, FIRST_ROW, 7.5f)));
    }

    @Test void T_10_20_aCellWrapsAtTheColumnWidthAndCutsALongWordOnceWithoutAnEmptyLine() {
        float width = COLUMN - 6;
        // A block note with two long links. The first one ends just before the column edge: it fits, the space after it
        // does not (padding with glyphs no wider than a space keeps the word within the width).
        var first = new StringBuilder("(https://example.test/");
        String pad = "fil/";
        for (int i = 0; width(first + " ") <= width; i++) { first.append(pad.charAt(i % pad.length())); }
        assertThat(width(first.toString())).as("fixture: the word fits").isLessThanOrEqualTo(width);
        assertThat(width(first + " ")).as("fixture: the space after it does not").isGreaterThan(width);
        String second = "https://example.test/avisos/pista-2/manteniment-anual-de-la-sorra";
        assertThat(width(" " + second)).as("fixture: the second link is longer than a line").isGreaterThan(width + 12);
        var block = blockCell(MONDAY.plusDays(1), "10:00", "12:00", "Pista 2", first.substring(1) + " " + second);

        var page = read(pdf.render(week(List.of("10:00"), List.of(block)), CLUB, "#1E6091", CA)).getFirst();

        var lines = page.texts.stream().filter(t -> Math.abs(t.x() - (79 + COLUMN)) < 0.05 && t.size() == 7.5f).toList();
        var texts = lines.stream().map(Text::text).toList();
        assertThat(texts).hasSizeGreaterThan(3).containsOnlyOnce(first.toString());
        int at = texts.indexOf(first.toString());
        // From the first link on, every line is built glyph by glyph against the width: none is wider.
        assertThat(lines.subList(at, lines.size())).allSatisfy(line -> assertThat(width(line.text())).isLessThanOrEqualTo(width));
        assertThat(texts.get(at + 1)).startsWith(" https://example.test/");
        assertThat(lines.get(at + 1).y()).isCloseTo(lines.get(at).y() - 9.5f, within(0.01f));
        assertThat(texts.getLast()).endsWith("sorra)");
    }

    // --- labels (lines 87, 88, 94) -------------------------------------------------------------------------------------------

    @Test void T_10_20_aClassLabelAddsTheWaitingCountOnlyWhenSomebodyWaitsAndMarksACancelledClass() {
        String base = "Grup A 6/6 Pista 1 · Neus";
        var full = classCell("class-a", MONDAY, "18:00", "Grup A", 6, "Pista 1", "Neus");
        // WAITLIST off: no `waiting` key at all.
        assertThat(pdf.label(full, CA)).isEqualTo(base);
        full.put("waiting", 0);
        assertThat(pdf.label(full, CA)).isEqualTo(base);
        full.put("waiting", 2);
        assertThat(pdf.label(full, CA)).isEqualTo(base + " · 2 espera");
        // A class cancelled by the club has its counters reset to 0 (ClassCancellationUseCase.java:34).
        var cancelled = classCell("class-b", MONDAY, "19:00", "Grup A", 0, "Pista 1", "Neus");
        cancelled.put("state", "CANCELLED");
        assertThat(pdf.label(cancelled, CA)).isEqualTo("Grup A 0/6 Pista 1 · Neus (anul·lada)");
    }

    @Test void T_10_20_aBlockLabelCarriesItsNoteInBracketsOnlyWhenThereIsOne() {
        String base = "10:00–12:00 Bloqueig Pista 2 — manteniment";
        assertThat(pdf.label(blockCell(MONDAY, "10:00", "12:00", "Pista 2", null), CA)).isEqualTo(base);
        assertThat(pdf.label(blockCell(MONDAY, "10:00", "12:00", "Pista 2", "Sorra nova"), CA)).isEqualTo(base + " (Sorra nova)");
    }

    // --- fixtures: the WeekAgendaQuery shape -------------------------------------------------------------------------------

    static Map<String, Object> week(List<String> rows, List<Map<String, Object>> cells) {
        var range = new LinkedHashMap<String, Object>();
        range.put("startDate", MONDAY); range.put("endDate", SATURDAY); range.put("relative", "CURRENT");
        var week = new LinkedHashMap<String, Object>();
        week.put("week", range); week.put("trainingSlotMinutes", null); week.put("filters", Map.of()); week.put("rows", rows); week.put("cells", cells);
        return week;
    }

    /** A class cell of the agenda (WAITLIST off: no `waiting`), capacity 6, one hour long. */
    static Map<String, Object> classCell(String id, LocalDate date, String time, String description, int booked, String ring, String instructor) {
        var cell = new LinkedHashMap<String, Object>();
        cell.put("date", date); cell.put("time", time); cell.put("endTime", LocalTime.parse(time).plusHours(1).toString()); cell.put("kind", "CLASS");
        cell.put("ringName", ring); cell.put("classId", id); cell.put("displayDescription", description); cell.put("ringColor", "#1E6091");
        cell.put("instructorName", instructor); cell.put("booked", booked); cell.put("capacity", 6); cell.put("state", "ACTIVE");
        cell.put("attendanceStatus", AttendanceStatusPort.AttendanceStatus.NONE);
        return cell;
    }

    /** A maintenance block of ring 2 with its optional note. */
    static Map<String, Object> blockCell(LocalDate date, String time, String endTime, String ring, String note) {
        var cell = new LinkedHashMap<String, Object>();
        cell.put("date", date); cell.put("time", time); cell.put("endTime", endTime); cell.put("kind", "BLOCK"); cell.put("ringName", ring);
        cell.put("blockId", "rb-1"); cell.put("reason", "MAINTENANCE"); cell.put("note", note); cell.put("createdByName", "Admin");
        return cell;
    }

    static List<String> times(Page page) {
        return page.texts.stream().filter(t -> t.size() == 7.5f && Math.abs(t.x() - 32) < 0.05).map(Text::text).toList();
    }

    /** Width at the cells' 7.5 pt of the font WeekAgendaPdf embeds. */
    static float width(String text) {
        try (var document = new PDDocument();
             var input = WeekAgendaPdf.class.getResourceAsStream("/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
            return PDType0Font.load(document, input).getStringWidth(text) * 7.5f / 1000;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    // --- content stream reader ----------------------------------------------------------------------------------------------

    record Text(String text, float x, float y, float size) {
        Text withText(String other) { return new Text(other, x, y, size); }
    }

    static final class Page {
        final List<Text> texts = new ArrayList<>();
        final List<String> fillColors = new ArrayList<>(), strokeColors = new ArrayList<>(), rects = new ArrayList<>(), moves = new ArrayList<>(),
                lines = new ArrayList<>();
        final List<Float> widths = new ArrayList<>();
        int fills, strokes, begins, ends;
    }

    static String describe(Text text) { return String.format(Locale.ROOT, "%s @ %.1f,%.1f size %.1f", text.text(), text.x(), text.y(), text.size()); }
    static String rgb(float r, float g, float b) { return String.format(Locale.ROOT, "%.3f/%.3f/%.3f", r, g, b); }
    static String rgb(float[] values) { return values.length == 3 ? rgb(values[0], values[1], values[2]) : "components " + values.length; }
    static String box(float x, float y, float w, float h) { return String.format(Locale.ROOT, "%.1f,%.1f %.1fx%.1f", x, y, w, h); }
    static String point(float x, float y) { return String.format(Locale.ROOT, "%.1f,%.1f", x, y); }

    /** Every page, every content stream (the footer is appended as a second stream). */
    static List<Page> read(byte[] bytes) {
        try (var document = Loader.loadPDF(bytes)) {
            var pages = new ArrayList<Page>();
            for (PDPage pdPage : document.getPages()) {
                var page = new Page();
                PDFont font = null;
                float size = 0, x = 0, y = 0;
                // The page parser reads the page's content streams one after the other (the footer is a second, appended one).
                var parser = new PDFStreamParser(pdPage);
                var operands = new ArrayList<Object>();
                for (Object token = parser.parseNextToken(); token != null; token = parser.parseNextToken()) {
                    if (!(token instanceof Operator operator)) { operands.add(token); continue; }
                    switch (operator.getName()) {
                        case "rg", "sc", "scn" -> page.fillColors.add(rgb(numbers(operands)));
                        case "RG", "SC", "SCN" -> page.strokeColors.add(rgb(numbers(operands)));
                        case "w" -> page.widths.add(numbers(operands)[0]);
                        case "re" -> { var r = numbers(operands); page.rects.add(box(r[0], r[1], r[2], r[3])); }
                        case "f" -> page.fills++;
                        case "m" -> { var p = numbers(operands); page.moves.add(point(p[0], p[1])); }
                        case "l" -> { var p = numbers(operands); page.lines.add(point(p[0], p[1])); }
                        case "S" -> page.strokes++;
                        case "BT" -> { page.begins++; x = 0; y = 0; }
                        case "ET" -> page.ends++;
                        case "Tf" -> { font = pdPage.getResources().getFont((COSName) operands.get(0)); size = ((COSNumber) operands.get(1)).floatValue(); }
                        case "Td" -> { var offset = numbers(operands); x = offset[0]; y = offset[1]; }
                        case "Tj" -> page.texts.add(new Text(decode(font, (COSString) operands.get(0)), x, y, size));
                        default -> { }
                    }
                    operands.clear();
                }
                pages.add(page);
            }
            return pages;
        } catch (IOException ex) { throw new UncheckedIOException(ex); }
    }

    static float[] numbers(List<Object> operands) {
        var numbers = operands.stream().filter(COSNumber.class::isInstance).map(COSNumber.class::cast).toList();
        float[] values = new float[numbers.size()];
        for (int i = 0; i < values.length; i++) { values[i] = numbers.get(i).floatValue(); }
        return values;
    }

    static String decode(PDFont font, COSString string) throws IOException {
        var input = new ByteArrayInputStream(string.getBytes());
        var text = new StringBuilder();
        while (input.available() > 0) {
            String unicode = font.toUnicode(font.readCode(input));
            text.append(unicode == null ? "�" : unicode);
        }
        return text.toString();
    }
}
