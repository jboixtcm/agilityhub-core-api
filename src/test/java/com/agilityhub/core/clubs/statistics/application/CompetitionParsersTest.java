package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.shared.domain.ApiException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;

class CompetitionParsersTest {
    static final String FCAG_URL = "https://app.fcagility.cat/federated/classification/division/128";
    static final String FLOW_URL = "https://www.flowagility.com/event/12345678-1234-1234-1234-123456789abc";
    // Fictional identities; real FCAG multirow/colspan layout and malformed closing thead.
    static final String FCAG = """
            <h1>Divisió d'honor / 2026 - 2027</h1><div><h3>Petita</h3><div><table>
            <thead><tr><th rowspan=2>Pos.</th><th rowspan=2>Llic.</th><th rowspan=2>Guia</th><th rowspan=2>Gos</th><th rowspan=2>Club</th>
            <th rowspan=2>Total</th><th colspan=4>Proves descartades</th><th rowspan=2>Total</th><th rowspan=2>Proves</th><th colspan=2>Copa indiv.</th><th>Copa equips</th></tr>
            <tr><th>1</th><th>2</th><th>3</th><th>4</th><td>Mànegues</td><td>Accés</td><td>Accés</td></tr><thead><tbody>
            <tr><td>2</td><td>0123</td><td>Laia Exemple</td><td>Núvol</td><td>Club de prova</td><td>150</td><td>4</td><td>3</td><td>2</td><td>1</td><td>140</td><td>5</td><td>2</td><td>Sí</td><td>No</td></tr>
            </tbody></table></div></div>
            """;
    static String flowRow(String run, String discipline, String point) {
        return "0123,Núvol,trial-1," + run + ",2026-10-10,Prova de tardor,II,M," + discipline + ",2,0,0,0,35.21,5.4,EXC," + point;
    }
    @Test void T_STATS_01_fcagUsesNetPointsAndKeepsLeadingZeroLicence() {
        var parsed = new FcagStandingsParser().parse(FCAG, FCAG_URL);
        assertThat(parsed.season()).isEqualTo("2026-2027");
        var row = parsed.entries().getFirst();
        assertThat(row.points()).isEqualByComparingTo("140");
        assertThat(row.license()).isEqualTo("0123");
        assertThat(row.position()).isEqualTo(2); assertThat(row.category()).isEqualTo("Petita");
        assertThat(row.dogName()).isEqualTo("Núvol");
    }
    @Test void T_STATS_02_fcagRejectsLoginEmptyPartialAndDuplicatePages() {
        var parser = new FcagStandingsParser();
        for (String html : java.util.List.of("<h1>Log In</h1>", FCAG.replace("<td>140</td>", ""),
                FCAG.replace("140", "unknown"), FCAG.replace("2027", "2029"), FCAG.replace("<tbody>", "<tbody>" + FCAG.substring(FCAG.indexOf("<tr><td>"), FCAG.indexOf("</tbody>"))))) {
            assertThatThrownBy(() -> parser.parse(html, FCAG_URL)).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_STATS_03_flowPreservesExplicitPointsAndQuotedNames() {
        var simple = new FlowResultsParser().parse(FlowResultsParser.HEADER + "\n" + flowRow("run-a", "AGILITY", "NONE"), FLOW_URL);
        assertThat(simple.entries().getFirst().point()).isEqualTo(Point.NONE);
        var quoted = new FlowResultsParser().parse("\uFEFF" + FlowResultsParser.HEADER + "\r\n"
                + flowRow("run-a", "AGILITY", "PA").replace("Núvol", "\"Núvol, blau\""), FLOW_URL);
        assertThat(quoted.entries().getFirst().dogName()).isEqualTo("Núvol, blau");
        assertThat(quoted.entries().getFirst().point()).isEqualTo(Point.PA);
    }
    @Test void T_STATS_04_flowRejectsDuplicateKeysMixedYearsInvalidNumbersAndTruncatedQuotes() {
        var valid = FlowResultsParser.HEADER + "\n" + flowRow("run-a", "AGILITY", "PA");
        for (String content : java.util.List.of(valid + "\n" + flowRow("run-a", "AGILITY", "PV"),
                valid + "\n" + flowRow("run-b", "JUMPING", "P").replace("2026-10-10", "2025-10-10"),
                valid.replace("35.21", "-1"), valid.replace("AGILITY", "UNKNOWN"), valid.replace("Núvol", "\"Núvol"),
                valid.replace(",PA", ",GUESS"))) {
            assertThatThrownBy(() -> new FlowResultsParser().parse(content, FLOW_URL)).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_STATS_05_sourceUrlsCannotTargetOtherHostsPortsCredentialsOrRedirectors() {
        for (String url : java.util.List.of("http://app.fcagility.cat/federated/classification/division/128",
                FCAG_URL + "?redirect=http://localhost", FCAG_URL.replace("app.fcagility.cat", "app.fcagility.cat.evil.test"),
                FCAG_URL.replace("app.fcagility.cat", "user@app.fcagility.cat"), FCAG_URL.replace("app.fcagility.cat", "app.fcagility.cat:444"),
                "https://127.0.0.1/federated/classification/division/128")) {
            assertThatThrownBy(() -> CompetitionValidation.url(Source.FCAG, url)).isInstanceOf(ApiException.class);
        }
    }
}
