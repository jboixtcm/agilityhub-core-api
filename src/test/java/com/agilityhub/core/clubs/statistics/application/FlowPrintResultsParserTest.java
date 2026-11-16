package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;

class FlowPrintResultsParserTest {
    static final String URL = "https://www.flowagility.com/zone/prints/run/12345678-1234-1234-1234-123456789abc/results";
    static String cell(String value) { return "<div>" + value + "</div>"; }
    static String pair(String first, String second) { return cell(cell(first) + cell(second)); }
    static String html(String point) {
        String header = pair("CLA", "DO") + pair("Guía", "Perro") + pair("Club", "Licencia")
                + java.util.List.of("T", "F", "R", "PR", "ET", "TP", "VEL", "CAL", "PTS").stream().map(FlowPrintResultsParserTest::cell).collect(java.util.stream.Collectors.joining());
        String row = pair("1º", "12") + pair("Laia Exemple", "Núvol") + pair("Club de prova", "0123")
                + java.util.List.of("35.12", "0", "0", "0.00", "0.00", "0.00", "5.40", "EXC_0", point).stream().map(FlowPrintResultsParserTest::cell).collect(java.util.stream.Collectors.joining());
        return "<div class='trial-caption'>Prova de tardor</div><div class='organ-caption'>Organizado por Club de prova - 2026.11.15</div>"
                + "<div class='results-caption'>Resultados - G3 / M / JP</div><div class='flex flex-row'>" + header + "</div><div class='flex flex-row'>" + row + "</div>";
    }
    @Test void T_STATS_12_printHtmlPreservesOfficialPointsAndStableIdentity() {
        var parser = new FlowPrintResultsParser(); var row = parser.parse(html("PA"), URL).entries().getFirst();
        assertThat(row.license()).isEqualTo("0123"); assertThat(row.discipline()).isEqualTo(Discipline.JUMPING);
        assertThat(row.point()).isEqualTo(Point.PA); assertThat(row.timeSeconds()).isEqualByComparingTo("35.12");
        assertThat(row.date()).isEqualTo(java.time.LocalDate.of(2026,11,15));
        var none = parser.parse(html("-"), URL).entries().getFirst(); assertThat(none.point()).isEqualTo(Point.NONE);
        assertThat(none.key()).isEqualTo(row.key());
        assertThat(parser.parse(html("PA"), URL.replace("www.", "").replace("/prints/", "/") + "#list_anchor_header").sourceUrl()).isEqualTo(URL);
        var otherRun = parser.parse(html("PV"), URL.replace("789abc", "789abd")).entries().getFirst();
        assertThat(otherRun.trialId()).isEqualTo(row.trialId()); assertThat(otherRun.key()).isNotEqualTo(row.key());
        var eliminated = parser.parse(html("-").replace("1º", "-").replace("35.12", "-").replace("5.40", "-").replace("EXC_0", "DISQ"), URL).entries().getFirst();
        assertThat(eliminated.position()).isNull(); assertThat(eliminated.timeSeconds()).isNull();
    }
    @Test void T_STATS_49_numberedRunsKeepTheirDisciplineAndDistinctRunIdentity() {
        var parser = new FlowPrintResultsParser();
        var first = parser.parse(html("1PA").replace(" / JP", " / AG1"), URL).entries().getFirst();
        var second = parser.parse(html("1PA").replace(" / JP", " / AG2"), URL.replace("789abc", "789abd")).entries().getFirst();
        assertThat(first.discipline()).isEqualTo(Discipline.AGILITY);
        assertThat(second.discipline()).isEqualTo(Discipline.AGILITY);
        assertThat(first.trialId()).isEqualTo(second.trialId());
        assertThat(first.key()).isNotEqualTo(second.key());
        assertThat(second.point()).isEqualTo(Point.PA);
        for (String run : java.util.List.of("JP1", "JP2")) {
            assertThat(parser.parse(html("1P").replace(" / JP", " / " + run), URL).entries().getFirst().discipline()).isEqualTo(Discipline.JUMPING);
        }
        for (String run : java.util.List.of("AG0", "JPX", "AG1/JP1")) {
            assertThatThrownBy(() -> parser.parse(html("1P").replace(" / JP", " / " + run), URL)).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_STATS_15_countedOfficialLabelsAndCombinedAwards() throws Exception {
        var parser = new FlowPrintResultsParser();
        assertThat(parser.parse(html("1P"), URL).entries().getFirst().point()).isEqualTo(Point.P);
        assertThat(parser.parse(html("1PA"), URL).entries().getFirst().point()).isEqualTo(Point.PA);
        assertThat(parser.parse(html("1PV"), URL).entries().getFirst().point()).isEqualTo(Point.PV);
        var combined = parser.parse(html("1PA / 1PV"), URL).entries().getFirst();
        assertThat(combined.point()).isEqualTo(Point.PA_PV);
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(combined.point())).isEqualTo("\"PA / PV\"");
        var stats = CompetitionStatistics.aggregate("dog", 2026, "2026-2027", java.util.List.of(
                new Result(Source.FLOW_AGILITY, URL, java.time.Instant.EPOCH, combined)));
        var jumping = stats.summaries().stream().filter(row -> row.discipline() == Discipline.JUMPING).findFirst().orElseThrow();
        assertThat(jumping.runs()).isEqualTo(1); assertThat(jumping.pa()).isEqualTo(1); assertThat(jumping.pv()).isEqualTo(1);
        assertThat(jumping.p()).isZero(); assertThat(stats.scoredTrials()).isEqualTo(1);
        for (var single : java.util.List.of(Point.P, Point.PA, Point.PV)) {
            var row = parser.parse(html("1" + single.name()), URL).entries().getFirst();
            var singleStats = CompetitionStatistics.aggregate("dog", 2026, "2026-2027", java.util.List.of(
                    new Result(Source.FLOW_AGILITY, URL, java.time.Instant.EPOCH, row))).summaries().getLast();
            assertThat(singleStats.p()).isEqualTo(single == Point.P ? 1 : 0);
            assertThat(singleStats.pa()).isEqualTo(single == Point.PA ? 1 : 0);
            assertThat(singleStats.pv()).isEqualTo(single == Point.PV ? 1 : 0);
        }
        assertThatThrownBy(() -> parser.parse(html("2PV"), URL)).isInstanceOf(ApiException.class);
    }
    @Test void T_STATS_13_loginChangedFormatMissingOfficialPointAndInvalidSourceFailClosed() {
        var parser = new FlowPrintResultsParser();
        for (String body : java.util.List.of("<form action='/user/login'>Login</form>", html("NEW_POINT"), html("PA").replace("PTS", "OTHER"), html("PA").replace("2026.11.15", "2026.99.15"), html("PA").replace("0123", ""))) {
            assertThatThrownBy(() -> parser.parse(body, URL)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> parser.parse(html("PA"), CompetitionParsersTest.FLOW_URL)).isInstanceOf(ApiException.class);
        String partial = html("PA") + "<div class='flex-row'>" + pair("2º", "13") + pair("Guia", "Gos") + pair("Club", "4567") + "</div>";
        assertThatThrownBy(() -> parser.parse(partial, URL)).isInstanceOf(ApiException.class);
    }
    @Test void T_STATS_14_environmentSessionsArePerClubAndNeverAcceptHeaderInjection() throws Exception {
        String fakeSession = "fictional-session-value-for-test";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var client = new FlowSourceClient(mapper.writeValueAsString(java.util.Map.of("club-a", fakeSession)));
        try (var ignored = TenantContext.open("club-a")) { assertThat(client.sessionCookie()).isEqualTo(fakeSession); }
        try (var ignored = TenantContext.open("club-b")) { assertThatThrownBy(client::sessionCookie).isInstanceOf(ApiException.class); }
        try (var ignored = TenantContext.open("../club-a")) { assertThatThrownBy(client::sessionCookie).isInstanceOf(ApiException.class); }
        for (String value : java.util.List.of("", "not-json", "[]", "null", "{\"club-a\":12}", mapper.writeValueAsString(java.util.Map.of("club-a", fakeSession + "\nX-Injected: bad")))) {
            try (var ignored = TenantContext.open("club-a")) { assertThatThrownBy(() -> new FlowSourceClient(value).sessionCookie()).isInstanceOf(ApiException.class); }
        }
    }
}
