package com.agilityhub.core.migration.application;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INC-68 round 2 (E11-T08, review #2): the Playoff planner reads a price's legacy BSON date with the rule the price
 * converter reads it with, so the migration and the billing choose the same price for a day.
 */
class PlayoffPlannerDatesTest {
    @ParameterizedTest
    @CsvSource({"UTC,2026-01-01", "Europe/Madrid,2026-01-01", "Europe/Madrid,2026-07-01", "Asia/Tokyo,2026-04-01",
            "America/New_York,2026-11-01"})
    void INC_68_round2_aLegacyPriceDateIsReadAsThePriceConverterReadsIt(String writerZone, String day) {
        var stored = Date.from(LocalDate.parse(day).atStartOfDay(ZoneId.of(writerZone)).toInstant());
        assertThat(PlayoffPlanner.businessDate(stored)).isEqualTo(LocalDate.parse(day));
        assertThat(PlayoffPlanner.businessDate(day)).isEqualTo(LocalDate.parse(day));
    }
}
