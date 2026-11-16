package com.agilityhub.core.clubs.statistics.application;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionValidation.*;

/** FlowAgility's individual run print HTML, observed 2026-11-15. Never infers P/PA/PV from a score. */
@Component
public class FlowPrintResultsParser {
    static final List<String> HEADERS = List.of("CLA DO", "Guía Perro", "Club Licencia", "T", "F", "R", "PR", "ET", "TP", "VEL", "CAL", "PTS");
    static final Pattern RUN = Pattern.compile("/zone/(?:prints/)?run/([a-fA-F0-9-]{36})/results");
    public Parsed parse(String content, String sourceUrl) {
        content(content); String safe = url(Source.FLOW_AGILITY, sourceUrl);
        var path = RUN.matcher(URI.create(safe).getPath());
        if (!path.matches()) { throw invalid("FLOW_RUN_URL_REQUIRED"); }
        String runId = path.group(1).toLowerCase(Locale.ROOT);
        var doc = Jsoup.parse(content);
        String trial = uniqueText(doc.select(".trial-caption"));
        String organiser = uniqueText(doc.select(".organ-caption"));
        String title = uniqueText(doc.select(".results-caption"));
        var dateMatch = Pattern.compile(".* - (20\\d{2})\\.(\\d{2})\\.(\\d{2})$").matcher(organiser);
        var titleMatch = Pattern.compile("(?:Resultados|Results|Resultats) - (G[123]) / ([A-Za-z]+) / (AG|JP)").matcher(title);
        if (!dateMatch.matches() || !titleMatch.matches() || trial.length() > 300) { throw invalid("UNRECOGNIZED_FLOW_HTML"); }
        LocalDate date;
        try { date = LocalDate.of(Integer.parseInt(dateMatch.group(1)), Integer.parseInt(dateMatch.group(2)), Integer.parseInt(dateMatch.group(3))); }
        catch (java.time.DateTimeException ex) { throw invalid("INVALID_RESULT_DATE"); }
        // The print view exposes a run UUID but no trial UUID. All its run pages share this trial/organiser/date identity.
        String trialId = hash(trial + "\n" + organiser);
        String year = Integer.toString(date.getYear());
        var discipline = titleMatch.group(3).equals("AG") ? Discipline.AGILITY : Discipline.JUMPING;
        boolean headerSeen = false; var entries = new ArrayList<Entry>(); var keys = new HashSet<String>();
        for (var row : doc.select("div.flex-row")) {
            var cells = row.children();
            if (cells.eachText().equals(HEADERS)) { headerSeen = true; continue; }
            if (!headerSeen || cells.isEmpty()) { continue; }
            boolean candidate = cells.size() == HEADERS.size() || cells.get(0).childrenSize() == 2;
            if (!candidate) { continue; }
            if (cells.size() != HEADERS.size() || cells.get(0).childrenSize() != 2
                    || cells.get(1).childrenSize() != 2 || cells.get(2).childrenSize() != 2) { throw invalid("UNRECOGNIZED_FLOW_HTML"); }
            String license = cells.get(2).child(1).text().trim();
            String dog = cells.get(1).child(1).text().trim();
            if (license.isBlank() || license.length() > 30 || dog.isBlank() || cells.eachText().stream().anyMatch(value -> value.length() > 300)) {
                throw invalid("MISSING_RESULT_IDENTITY");
            }
            String key = trialId + ":" + runId + ":" + license;
            if (!keys.add(key)) { throw invalid("DUPLICATE_RESULT"); }
            String pointText = cells.get(11).text().trim(); Point point;
            try {
                point = switch (pointText) {
                    case "-" -> Point.NONE;
                    case "1P" -> Point.P;
                    case "1PA" -> Point.PA;
                    case "1PV" -> Point.PV;
                    case "1PA / 1PV", "PA / PV" -> Point.PA_PV;
                    default -> Point.valueOf(pointText);
                };
            }
            catch (IllegalArgumentException failure) { throw invalid("UNKNOWN_OFFICIAL_POINT"); }
            String rank = cells.get(0).child(0).text().replace("º", "").replace("°", "");
            BigDecimal time = number(cells.get(3).text()), speed = number(cells.get(9).text());
            if ((time != null && time.signum() <= 0) || (speed != null && speed.compareTo(BigDecimal.valueOf(20)) > 0)) { throw invalid("INVALID_NUMBER"); }
            entries.add(new Entry(key, license, dog, cells.get(1).child(0).text(), cells.get(2).child(0).text(), year, null,
                    titleMatch.group(2), rank.equals("-") ? null : FcagStandingsParser.rank(rank), null, trialId, runId, date, trial,
                    titleMatch.group(1), discipline, integer(cells.get(4).text()), integer(cells.get(5).text()), number(cells.get(8).text()),
                    time, speed, cells.get(10).text(), point));
            if (entries.size() > MAX_ROWS) { throw invalid("TOO_MANY_ROWS"); }
        }
        if (!headerSeen || entries.isEmpty()) { throw invalid("UNRECOGNIZED_FLOW_HTML"); }
        return new Parsed(Source.FLOW_AGILITY, safe, year, List.copyOf(entries));
    }
    private static String uniqueText(List<Element> elements) {
        var texts = elements.stream().map(Element::text).distinct().toList();
        if (texts.size() != 1 || texts.getFirst().isBlank()) { throw invalid("UNRECOGNIZED_FLOW_HTML"); }
        return texts.getFirst();
    }
    private static BigDecimal number(String value) { return value.equals("-") ? null : FcagStandingsParser.decimal(value); }
    private static Integer integer(String value) {
        if (value.equals("-")) { return null; }
        try { int n = Integer.parseInt(value); if (n >= 0 && n <= 100) { return n; } }
        catch (NumberFormatException ignored) { }
        throw invalid("INVALID_NUMBER");
    }
}
