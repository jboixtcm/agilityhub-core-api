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
    @Test void T_STATS_07_timePenaltiesAreNotClearAndOtherSeasonsStayOut() {
        var row = new FlowResultsParser().parse(FlowResultsParser.HEADER + "\n"
                + CompetitionParsersTest.flowRow("a", "AGILITY", "NONE").replace(",0,35.21", ",1.5,35.21"), CompetitionParsersTest.FLOW_URL).entries().getFirst();
        var result = new Result(Source.FLOW_AGILITY, CompetitionParsersTest.FLOW_URL, Instant.parse("2026-11-15T10:00:00Z"), row);
        assertThat(CompetitionStatistics.aggregate("dog", 2026, "2026-2027", List.of(result)).summaries().getFirst().clearRuns()).isZero();
        assertThat(CompetitionStatistics.aggregate("dog", 2025, "2025-2026", List.of(result)).results()).isEmpty();
        assertThat(CompetitionStatistics.aggregate("dog", 2025, "2025-2026", List.of(result)).summaries().getFirst().averageSpeedMps()).isNull();
    }
}
