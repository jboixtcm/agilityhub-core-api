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
