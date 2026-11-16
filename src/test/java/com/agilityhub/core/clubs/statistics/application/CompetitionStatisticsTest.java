package com.agilityhub.core.clubs.statistics.application;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.agilityhub.core.clubs.statistics.application.CompetitionModels.*;
import static org.assertj.core.api.Assertions.*;

class CompetitionStatisticsTest {
    @Test void T_STATS_06_trialsCountOnceAndClearRoundsDoNotInventPoints() {
        var entries = new FlowResultsParser().parse(FlowResultsParser.HEADER + "\n"
                + CompetitionParsersTest.flowRow("a", "AGILITY", "PA") + "\n"
                + CompetitionParsersTest.flowRow("j", "JUMPING", "NONE"), CompetitionParsersTest.FLOW_URL).entries();
        var at = Instant.parse("2026-11-15T10:00:00Z");
        var first = new Result(Source.FLOW_AGILITY, CompetitionParsersTest.FLOW_URL, at, entries.getFirst());
        var duplicate = new Result(Source.FLOW_AGILITY, CompetitionParsersTest.FLOW_URL + "/copy", at.plusSeconds(1), entries.getFirst());
        var jumping = new Result(Source.FLOW_AGILITY, CompetitionParsersTest.FLOW_URL, at, entries.get(1));
        var stats = CompetitionStatistics.aggregate("dog", 2026, "2026-2027", List.of(first, duplicate, jumping));
        assertThat(stats.results()).hasSize(2); assertThat(stats.scoredTrials()).isEqualTo(1);
        assertThat(stats.summaries().getFirst().pa()).isEqualTo(1);
        assertThat(stats.summaries().get(1).clearRuns()).isEqualTo(1);
        assertThat(stats.summaries().get(1).p()).isZero();
        assertThat(stats.lastImportedAt()).isEqualTo(at.plusSeconds(1));
    }
    @Test void T_STATS_51_awardsAreClearWhenPenaltyIsMissingAndUnknownOutcomesAreNotZeros() {
        var parser = new FlowPrintResultsParser();
        var rows = new java.util.ArrayList<Entry>();
        for (String point : List.of("P", "PV", "PA", "1PA / 1PV")) {
            rows.add(parser.parse(FlowPrintResultsParserTest.html(point).replace("0.00", "-").replace("35.12", "-").replace("1º", "-"), FlowPrintResultsParserTest.URL).entries().getFirst());
        }
        String missing = FlowPrintResultsParserTest.html("-").replace("0.00", "-");
        rows.add(parser.parse(missing, FlowPrintResultsParserTest.URL).entries().getFirst()); // Explicit EXC_0, no award.
        rows.add(parser.parse(missing.replace("EXC_0", "EXC").replace("<div>0</div>", "<div>1</div>"), FlowPrintResultsParserTest.URL).entries().getFirst());
        rows.add(parser.parse(missing.replace("EXC_0", "DISQ"), FlowPrintResultsParserTest.URL).entries().getFirst());
        rows.add(parser.parse(missing.replace("EXC_0", ""), FlowPrintResultsParserTest.URL).entries().getFirst());
        var summary = CompetitionStatistics.summarize(rows).getLast();
        assertThat(summary.runs()).isEqualTo(8);
        assertThat(summary.clearRuns()).isEqualTo(5);
        assertThat(summary.p()).isEqualTo(1); assertThat(summary.pv()).isEqualTo(2); assertThat(summary.pa()).isEqualTo(2);
        assertThat(summary.eliminatedRuns()).isEqualTo(1); assertThat(summary.outcomeSamples()).isEqualTo(7);
        assertThat(summary.clearRatePercent()).isEqualByComparingTo("71.4");
        assertThat(summary.eliminatedRatePercent()).isEqualByComparingTo("14.3");
        assertThat(summary.speedSamples()).isEqualTo(7); assertThat(summary.averageSpeedMps()).isEqualByComparingTo("5.40");
        var empty = CompetitionStatistics.summarize(List.of()).getFirst();
        assertThat(empty.clearRatePercent()).isNull(); assertThat(empty.eliminatedRatePercent()).isNull();
        assertThat(empty.averageSpeedMps()).isNull();
    }
    @Test void T_STATS_07_timePenaltiesAreNotClearAndOtherSeasonsStayOut() {
        var row = new FlowResultsParser().parse(FlowResultsParser.HEADER + "\n"
                + CompetitionParsersTest.flowRow("a", "AGILITY", "NONE").replace(",0,35.21", ",1.5,35.21"), CompetitionParsersTest.FLOW_URL).entries().getFirst();
        var result = new Result(Source.FLOW_AGILITY, CompetitionParsersTest.FLOW_URL, Instant.parse("2026-11-15T10:00:00Z"), row);
        assertThat(CompetitionStatistics.aggregate("dog", 2026, "2026-2027", List.of(result)).summaries().getFirst().clearRuns()).isZero();
        assertThat(CompetitionStatistics.aggregate("dog", 2025, "2025-2026", List.of(result)).results()).isEmpty();
        assertThat(CompetitionStatistics.aggregate("dog", 2025, "2025-2026", List.of(result)).summaries().getFirst().averageSpeedMps()).isNull();
    }
}
