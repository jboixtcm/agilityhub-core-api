package com.agilityhub.core.courses.application;

import com.agilityhub.core.courses.domain.AgilityHubLevel;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** E9-T03: R-16-09's `levelLabel` and the `RingChanged{deactivated}` filter of S16 §7's consumer. */
class RingSetupLabelsTest {
    static Map<String, String> codes() {
        var codes = new LinkedHashMap<String, String>(); codes.put("lv-c", "C"); codes.put("lv-d", "D"); codes.put("lv-e", "E");
        return codes;
    }

    @Test void T_16_09_theLevelLabelJoinsTheClubCodesInCatalogOrderElseTheAgilityHubLevel() {
        assertThat(LevelLabels.label(codes(), List.of("lv-d", "lv-c"), AgilityHubLevel.HARD)).isEqualTo("C–D");
        assertThat(LevelLabels.label(codes(), List.of("lv-missing"), AgilityHubLevel.EASY)).isEqualTo("EASY");
        assertThat(LevelLabels.label(codes(), List.of(), null)).isNull();
        assertThat(LevelLabels.label(Map.of(), List.of("lv-c"), null)).isNull();
    }

    @Test void T_16_08_onlyARingChangeThatDeactivatesItEndsTheSetup() {
        assertThat(RingSetupConsumers.deactivated(Map.of("diff", Map.of("active", Map.of("before", true, "after", false))))).isTrue();
        assertThat(RingSetupConsumers.deactivated(Map.of("diff", Map.of("active", Map.of("before", false, "after", true))))).isFalse();
        assertThat(RingSetupConsumers.deactivated(Map.of("diff", Map.of("name", Map.of("before", "A", "after", "B"))))).isFalse();
        assertThat(RingSetupConsumers.deactivated(Map.of("diff", "not a map"))).isFalse();
        assertThat(RingSetupConsumers.deactivated(Map.of())).isFalse();
        assertThat(RingSetupConsumers.deactivated(null)).isFalse();
    }
}
