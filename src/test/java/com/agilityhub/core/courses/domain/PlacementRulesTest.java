package com.agilityhub.core.courses.domain;

import com.agilityhub.core.courses.domain.CourseParts.PlacementWarning;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E9-T03: S16 R-16-05's policy over course-core's warnings (§14.4, E77). */
class PlacementRulesTest {
    static PlacementWarning warning(String id, WarningRule rule, WarningSeverity severity) {
        return new PlacementWarning(id, rule, severity, "Example", null, null, null, null, null, null);
    }

    @Test void T_16_07_onlyACriticalWarningBlocksAndForceStoresIt() {
        var margin = warning("w-border", WarningRule.BORDER_CLEARANCE, WarningSeverity.WARNING);
        var inventory = warning("w-inv", WarningRule.OBSTACLE_INVENTORY, WarningSeverity.INFO);
        var nogo = warning("w-nogo", WarningRule.RING_NO_GO, WarningSeverity.CRITICAL);
        var outside = warning("w-out", WarningRule.OUTSIDE_RING, WarningSeverity.CRITICAL);
        assertThat(PlacementRules.outcome(List.of(), false)).isEqualTo(PlacementRules.Outcome.STORE);
        assertThat(PlacementRules.outcome(List.of(margin, inventory), false)).isEqualTo(PlacementRules.Outcome.STORE);
        assertThat(PlacementRules.outcome(List.of(margin, inventory), true)).isEqualTo(PlacementRules.Outcome.STORE);
        assertThat(PlacementRules.outcome(List.of(margin, nogo), false)).isEqualTo(PlacementRules.Outcome.BLOCKED);
        assertThat(PlacementRules.outcome(List.of(margin, nogo), true)).isEqualTo(PlacementRules.Outcome.FORCED);
        assertThat(PlacementRules.critical(List.of(outside, margin, nogo, inventory))).containsExactly(outside, nogo);
        assertThat(PlacementRules.critical(List.of(margin, inventory))).isEmpty();
    }
}
