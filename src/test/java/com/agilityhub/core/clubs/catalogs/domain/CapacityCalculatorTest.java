package com.agilityhub.core.clubs.catalogs.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.agilityhub.core.clubs.catalogs.domain.CapacityCalculator.*;

class CapacityCalculatorTest {
    private static final Thresholds THRESHOLDS = new Thresholds(240, 190, 150);
    @Test void T_05_01_classCapacityUsesMinimumOrConfiguredDefault() {
        assertThat(forLevels(List.of(5, 4), true, 7)).isEqualTo(4);
        assertThat(forLevels(List.of(), true, 7)).isEqualTo(7);
        assertThat(forLevels(List.of(4), false, 7)).isEqualTo(7);
        assertThat(forLevels(List.of(5), true, 7)).isEqualTo(5);
    }
    @Test void T_05_05_ringCapacityUsesOverrideOrConfiguredDefault() {
        assertThat(forRing(null, 1)).isEqualTo(1);
        assertThat(forRing(2, 1)).isEqualTo(2);
        assertThat(forRing(null, 3)).isEqualTo(3);
    }
    @Test void T_05_01_D3CoverageMatchesEveryMockupRow() {
        // D3 published totals are display fixtures, independent of S06's future template storage.
        String[][] rows = {
            {"50", "50", "24", "19", "208", "263", "OK"},
            {"100", "70.5", "43", "31", "233", "227", "TIGHT"},
            {"120", "75", "39", "28", "308", "268", "OK"},
            {"105", "60.5", "42", "35", "250", "173", "SHORT"},
            {"95", "55", "35", "27", "271", "204", "TIGHT"},
            {"80", "45.5", "31", "22", "258", "207", "TIGHT"},
            {"28", "14", "16", "10", "175", "140", "EXPAND"},
            {"26", "12.5", "12", "7", "217", "179", "SHORT"}
        };
        for (String[] row : rows) {
            var result = coverage(new BigDecimal(row[0]), new BigDecimal(row[1]), new DogCounts(Integer.parseInt(row[2]), Integer.parseInt(row[3])), THRESHOLDS);
            assertThat(result.maximumPlaces()).isEqualByComparingTo(row[0]);
            assertThat(result.proportionalPlaces()).isEqualByComparingTo(row[1]);
            assertThat(result.maximumPercentOfAll().setScale(0, RoundingMode.HALF_UP)).isEqualByComparingTo(row[4]);
            assertThat(result.proportionalPercentOfActive().setScale(0, RoundingMode.HALF_UP)).isEqualByComparingTo(row[5]);
            assertThat(result.band()).isEqualTo(Band.valueOf(row[6]));
        }
    }
    @Test void T_05_01_sharedClassesSplitPlacesAndZeroDemandHasNoRatio() {
        var result = coverage(List.of(new ClassPlaces(5, List.of("A", "B")), new ClassPlaces(4, List.of("A")), new ClassPlaces(7, List.of())),
                Map.of("A", new DogCounts(4, 2), "B", new DogCounts(0, 0), "C", new DogCounts(2, 0)), THRESHOLDS);
        assertThat(result.get("A").maximumPlaces()).isEqualByComparingTo("9");
        assertThat(result.get("A").proportionalPlaces()).isEqualByComparingTo("6.5");
        assertThat(result.get("A").maximumPercentOfAll()).isEqualByComparingTo("225");
        assertThat(result.get("A").proportionalPercentOfActive()).isEqualByComparingTo("325");
        assertThat(result.get("B").band()).isEqualTo(Band.NO_ACTIVE_DOGS);
        assertThat(result.get("B").maximumPercentOfAll()).isNull();
        assertThat(result.get("C").maximumPercentOfAll()).isEqualByComparingTo("0");
        assertThat(result.get("C").proportionalPercentOfActive()).isNull();
    }
    @Test void T_05_01_thresholdBoundariesUseUnroundedRatiosAndCustomConfiguration() {
        var expected = Map.of("240.0000000001", Band.OK, "240.001", Band.OK, "240", Band.TIGHT, "190", Band.TIGHT, "189.99", Band.SHORT, "150", Band.SHORT, "149.99", Band.EXPAND);
        expected.forEach((ratio, band) -> assertThat(coverage(new BigDecimal(ratio), new BigDecimal(ratio), new DogCounts(100, 100), THRESHOLDS).band()).isEqualTo(band));
        assertThat(coverage(BigDecimal.TEN, BigDecimal.TEN, new DogCounts(5, 5), new Thresholds(180, 140, 100)).band()).isEqualTo(Band.OK);
    }
    @Test void T_05_01_invalidCountsClassesAndThresholdsAreRejected() {
        for (int[] counts : List.of(new int[]{-1, 0}, new int[]{1, -1}, new int[]{1, 2})) {
            assertThatThrownBy(() -> new DogCounts(counts[0], counts[1])).isInstanceOf(IllegalArgumentException.class);
        }
        for (int[] thresholds : List.of(new int[]{240, 190, -1}, new int[]{240, 150, 150}, new int[]{190, 190, 150})) {
            assertThatThrownBy(() -> new Thresholds(thresholds[0], thresholds[1], thresholds[2])).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new ClassPlaces(-1, List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassPlaces(5, List.of("A", "A"))).isInstanceOf(IllegalArgumentException.class);
        for (String[] places : List.of(new String[]{"-1", "0"}, new String[]{"1", "-1"}, new String[]{"1", "2"})) {
            assertThatThrownBy(() -> coverage(new BigDecimal(places[0]), new BigDecimal(places[1]), new DogCounts(1, 1), THRESHOLDS)).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
