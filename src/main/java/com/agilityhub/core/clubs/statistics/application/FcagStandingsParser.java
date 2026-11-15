package com.agilityhub.core.clubs.statistics.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionValidation.*;

/** The FCAG table has raw and net Total columns; the last Total is the official standing. */
@Component
public class FcagStandingsParser {
    private static final Pattern TITLE = Pattern.compile("(.+?)\\s*/\\s*(20\\d{2})\\s*-\\s*(20\\d{2})");
    public Parsed parse(String html, String url) {
        content(html);
        var document = Jsoup.parse(html);
        var heading = document.selectFirst("h1");
        var title = TITLE.matcher(heading == null ? "" : heading.text());
        if (!title.matches() || Integer.parseInt(title.group(3)) != Integer.parseInt(title.group(2)) + 1) {
            throw invalid("UNRECOGNIZED_FCAG_PAGE");
        }
        String division = title.group(1).trim(), season = title.group(2) + "-" + title.group(3);
        if (division.length() > 300) { throw invalid("FIELD_TOO_LONG"); }
        var entries = new ArrayList<Entry>(); var keys = new HashSet<String>();
        for (Element table : document.select("table")) {
            var header = table.selectFirst("tr");
            if (header == null) { continue; }
            int column = 0, license = -1, dog = -1, handler = -1, club = -1, position = -1, total = -1;
            for (Element cell : header.children()) {
                switch (cell.text().trim().toLowerCase(Locale.ROOT)) {
                    case "llic." -> license = column;
                    case "gos" -> dog = column;
                    case "guia" -> handler = column;
                    case "club" -> club = column;
                    case "pos." -> position = column;
                    case "total" -> total = column;
                    default -> { }
                }
                column += span(cell);
            }
            if (license < 0 || dog < 0 || handler < 0 || club < 0 || position < 0 || total < 0) {
                throw invalid("UNRECOGNIZED_FCAG_COLUMNS");
            }
            String category = category(table);
            if (category.length() > 100) { throw invalid("FIELD_TOO_LONG"); }
            boolean dataSeen = false;
            for (Element row : table.select("tr")) {
                // FCAG's secondary header mixes th and td (cup/qualification labels).
                boolean headingRow = row.children().stream().anyMatch(cell -> "th".equals(cell.tagName()));
                if (headingRow && !dataSeen) { continue; }
                if (headingRow) { throw invalid("UNRECOGNIZED_FCAG_COLUMNS"); }
                var cells = row.children().stream().filter(cell -> "td".equals(cell.tagName())).toList();
                if (cells.isEmpty()) { continue; }
                dataSeen = true;
                if (cells.size() < column) { throw invalid("INCOMPLETE_FCAG_ROW"); }
                String number = cells.get(license).text().trim();
                if (number.isBlank() || number.length() > 30) { throw invalid("MISSING_LICENSE"); }
                if (java.util.List.of(dog, handler, club).stream().anyMatch(index -> cells.get(index).text().length() > 300)) { throw invalid("FIELD_TOO_LONG"); }
                String key = hash(season + ":" + division + ":" + category + ":" + number);
                if (!keys.add(key)) { throw invalid("DUPLICATE_RESULT"); }
                entries.add(new Entry(key, number, cells.get(dog).text(), cells.get(handler).text(), cells.get(club).text(),
                        season, division, category, rank(cells.get(position).text()), decimal(cells.get(total).text()),
                        null, null, null, null, null, null, null, null, null, null, null, null, Point.NONE));
                if (entries.size() > MAX_ROWS) { throw invalid("TOO_MANY_ROWS"); }
            }
        }
        if (entries.isEmpty()) { throw invalid("NO_RESULTS"); }
        return new Parsed(Source.FCAG, url(Source.FCAG, url), season, java.util.List.copyOf(entries));
    }
    private static int span(Element cell) {
        if (!cell.hasAttr("colspan")) { return 1; }
        try { int n = Integer.parseInt(cell.attr("colspan")); if (n > 0 && n < 100) { return n; } }
        catch (NumberFormatException ignored) { }
        throw invalid("UNRECOGNIZED_FCAG_COLUMNS");
    }
    private static String category(Element table) {
        for (Element node = table; node != null; node = node.parent()) {
            for (Element previous = node.previousElementSibling(); previous != null; previous = previous.previousElementSibling()) {
                if ("h3".equals(previous.tagName()) && !previous.text().isBlank()) { return previous.text(); }
            }
        }
        throw invalid("MISSING_CATEGORY");
    }
    static Integer rank(String value) {
        if (value.isBlank() || value.equals("-")) { return null; }
        try { int n = Integer.parseInt(value); if (n > 0) { return n; } }
        catch (NumberFormatException ignored) { }
        throw invalid("INVALID_POSITION");
    }
    static BigDecimal decimal(String value) {
        try { var n = new BigDecimal(value.trim().replace(',', '.')); if (n.signum() >= 0 && n.precision() <= 9 && n.scale() >= -6 && n.scale() <= 6 && n.compareTo(BigDecimal.valueOf(1_000_000)) <= 0) { return n; } }
        catch (NumberFormatException ignored) { }
        throw invalid("INVALID_NUMBER");
    }
}
