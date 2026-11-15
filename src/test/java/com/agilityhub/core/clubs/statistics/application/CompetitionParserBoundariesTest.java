package com.agilityhub.core.clubs.statistics.application;

import com.agilityhub.core.shared.domain.ApiException;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;

class CompetitionParserBoundariesTest {
    @Test void T_STATS_27_providerInputsRejectUnboundedAndAmbiguousRepresentations() {
        for (String value : Arrays.asList(null, "", "  ", "a".repeat(CompetitionValidation.MAX_BYTES + 1))) {
            assertThatThrownBy(() -> CompetitionValidation.content(value)).isInstanceOf(ApiException.class);
        }
        for (String url : Arrays.asList(null, "x".repeat(501), "https://[", CompetitionParsersTest.FCAG_URL + "#x",
                CompetitionParsersTest.FCAG_URL.replace("128", "%31%32%38"), "https://app.fcagility.cat/federated/../classification/division/128")) {
            assertThatThrownBy(() -> CompetitionValidation.url(Source.FCAG, url)).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> CompetitionValidation.url(null, CompetitionParsersTest.FCAG_URL)).isInstanceOf(ApiException.class);
        for (String url : List.of(CompetitionParsersTest.FLOW_URL + "#other", CompetitionParsersTest.FLOW_URL + "?x=1", CompetitionParsersTest.FLOW_URL.replace("flowagility.com", "evil.test"))) {
            assertThatThrownBy(() -> CompetitionValidation.url(Source.FLOW_AGILITY, url)).isInstanceOf(ApiException.class);
        }
        for (String value : List.of("NaN", "-1", "1234567890", "1e1000000", "1e-1000000", "1000001")) {
            assertThatThrownBy(() -> FcagStandingsParser.decimal(value)).isInstanceOf(ApiException.class);
        }
    }
    @Test void T_STATS_28_fcagChangedHeadersCategoriesAndNumbersFailWithoutPartialImport() {
        var parser = new FcagStandingsParser(); String input = CompetitionParsersTest.FCAG;
        for (String bad : List.of(input.replace("<h1>", "<h2>").replace("</h1>", "</h2>"), input.replace("<h3>Petita</h3>", ""), input.replace("</tbody>", "<tr><th>Unexpected mid-table header</th></tr></tbody>"),
                input.replace("<h3>Petita</h3>", "<h3> </h3>"), input.replace("Divisió d'honor", "a".repeat(301)), input.replace("Petita", "a".repeat(101)), input.replace("Núvol", "a".repeat(301)), input.replace("Llic.", "License"), input.replace("colspan=4", "colspan=x"),
                input.replace("colspan=4", "colspan=0"), input.replace("colspan=4", "colspan=100"), input.replace("<td>0123</td>", "<td></td>"),
                input.replace("0123", "1".repeat(31)), input.replace("<td>2</td><td>0123", "<td>0</td><td>0123"),
                input.replace("<td>2</td><td>0123", "<td>x</td><td>0123"), input.substring(0,input.indexOf("<div>")))) {
            assertThatThrownBy(() -> parser.parse(bad, CompetitionParsersTest.FCAG_URL)).isInstanceOf(ApiException.class);
        }
        assertThat(parser.parse(input.replace("<td>2</td><td>0123", "<td>-</td><td>0123"), CompetitionParsersTest.FCAG_URL).entries().getFirst().position()).isNull();
        String row = input.substring(input.indexOf("<tr><td>"), input.indexOf("</tbody>"));
        StringBuilder many = new StringBuilder(input.substring(0,input.indexOf("<tr><td>")));
        for (int i=0;i<=CompetitionValidation.MAX_ROWS;i++) many.append(row.replace("0123", Integer.toString(i)));
        many.append("</tbody></table>");
        assertThatThrownBy(() -> parser.parse(many.toString(), CompetitionParsersTest.FCAG_URL)).isInstanceOf(ApiException.class);
    }
    @Test void T_STATS_29_csvMalformedRecordsAndMissingIdentitiesNeverBecomeOfficialResults() {
        var parser = new FlowResultsParser(); String row = CompetitionParsersTest.flowRow("run-a", "AGILITY", "PA");
        for (String body : List.of("", FlowResultsParser.HEADER, "wrong\n"+row, FlowResultsParser.HEADER+"\na,b", FlowResultsParser.HEADER+"\n"+row.replace("Núvol","a".repeat(301)),
                FlowResultsParser.HEADER+"\n"+row.replace("Núvol","a\"b"), FlowResultsParser.HEADER+"\n"+row.replace("Núvol","\"Núvol\"x"))) {
            assertThatThrownBy(() -> parser.parse(body, CompetitionParsersTest.FLOW_URL)).isInstanceOf(ApiException.class);
        }
        Map<Integer,List<String>> invalid = Map.of(0,List.of("", "1".repeat(31)),1,List.of(""),2,List.of("", "bad/id"),3,List.of("", "bad:id"),
                4,List.of("not-date", "1999-01-01", "2101-01-01"),5,List.of(""),6,List.of(""),7,List.of(""),10,List.of("-1","101","x"),13,List.of("0"));
        invalid.forEach((index,values) -> values.forEach(value -> {
            String[] cells=row.split(","); cells[index]=value;
            assertThatThrownBy(() -> parser.parse(FlowResultsParser.HEADER+"\n"+String.join(",",cells), CompetitionParsersTest.FLOW_URL)).isInstanceOf(ApiException.class);
        }));
        assertThatThrownBy(() -> parser.parse(FlowResultsParser.HEADER+"\n"+row.replace("5.4", "21"), CompetitionParsersTest.FLOW_URL)).isInstanceOf(ApiException.class);
        String[] cells=row.split(","); for(int index=9;index<=14;index++) cells[index]=""; cells[15]="DISQ"; cells[16]="NONE";
        var eliminated=parser.parse(FlowResultsParser.HEADER+"\r\n\r\n"+String.join(",",cells)+"\r\n", CompetitionParsersTest.FLOW_URL).entries().getFirst();
        assertThat(eliminated.timeSeconds()).isNull(); assertThat(eliminated.speedMps()).isNull(); assertThat(eliminated.faults()).isNull();
        assertThat(FlowResultsParser.csv("\"a\"\"b\",c\n\"d\ne\",f")).containsExactly(List.of("a\"b","c"),List.of("d\ne","f"));
        assertThatThrownBy(() -> FlowResultsParser.csv("row\n".repeat(CompetitionValidation.MAX_ROWS+3))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> FlowResultsParser.csv("row\n".repeat(CompetitionValidation.MAX_ROWS+1)+"last")).isInstanceOf(ApiException.class);
    }
    @Test void T_STATS_30_printChangedCaptionsAndNumericCellsCannotSilentlyDropRows() {
        var parser=new FlowPrintResultsParser(); String html=FlowPrintResultsParserTest.html("PA");
        for(String body:List.of(html.replace("35.12","0"),html.replace("5.40","21"),html.replace("<div>0</div>","<div>101</div>"),
                html.replace("<div>0</div>","<div>x</div>"),html.replace("Núvol",""),html.replace("Prova de tardor","a".repeat(301)),
                html.replace("0123","1".repeat(31)),html.replace("G3 / M / JP","G9 / M / JP"),html+"<div class='trial-caption'>Different</div>",
                html.replace("<div>Laia Exemple</div><div>Núvol</div>","<div>Núvol</div>"))) {
            assertThatThrownBy(() -> parser.parse(body,FlowPrintResultsParserTest.URL)).isInstanceOf(ApiException.class);
        }
        String missing=html.replace("<div>0</div>","<div>-</div>");
        assertThat(parser.parse(missing,FlowPrintResultsParserTest.URL).entries().getFirst().faults()).isNull();
        var row=html.substring(html.lastIndexOf("<div class='flex flex-row'>"));
        assertThatThrownBy(() -> parser.parse(html+row,FlowPrintResultsParserTest.URL)).isInstanceOf(ApiException.class);
    }
}
