package com.agilityhub.core.clubs.catalogs.persistence;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * INC-68 round 2 (E11-T08): a validity day stored as a BSON date is the writer JVM's local midnight. The converter reads the
 * day that writer meant whatever the zone of the JVM that runs the test. INC-74 (E11-T13): the rule depends on the writer's
 * UTC offset at that midnight, not on its zone: it holds for a writer offset from −11:59 to +12:00 (Pacific/Auckland only in
 * its winter, +12:00; its summer is +13:00), and a writer beyond reads a day off.
 */
class PriceDateConverterTest {
    private final PriceDateConverter converter = new PriceDateConverter();

    @ParameterizedTest
    @CsvSource({"UTC,2026-01-01", "Europe/Madrid,2026-01-01", "Europe/Madrid,2026-07-01", "Europe/Madrid,2026-03-29",
            "Europe/Athens,2026-10-25", "Asia/Tokyo,2026-04-01", "Pacific/Auckland,2026-07-01", "America/New_York,2026-11-01",
            "America/Sao_Paulo,2026-02-28", "Pacific/Honolulu,2026-12-31"})
    void INC_68_round2_aLegacyDateReadsTheDayItsWriterMeant(String writerZone, String day) {
        var stored = Date.from(LocalDate.parse(day).atStartOfDay(ZoneId.of(writerZone)).toInstant());
        assertThat(converter.read(stored, null)).isEqualTo(LocalDate.parse(day));
    }

    /**
     * INC-74 (E11-T13), the documented limit (`docs/DEPLOY.md`, «Time zone of a legacy date»): the edges of the writer offsets
     * read right, and a date written by a JVM at +13:00 (Pacific/Tongatapu) reads a day early.
     */
    @Test void INC_74_theRuleIsByWriterOffsetAndADateWrittenAtPlusThirteenReadsADayEarly() {
        var day = LocalDate.parse("2026-01-01");
        for (String edge : new String[] {"+12:00", "-11:59"}) {
            assertThat(converter.read(Date.from(day.atStartOfDay(java.time.ZoneOffset.of(edge)).toInstant()), null)).as(edge).isEqualTo(day);
        }
        assertThat(converter.read(Date.from(day.atStartOfDay(java.time.ZoneOffset.of("+13:00")).toInstant()), null)).isEqualTo(day.minusDays(1));
        assertThat(converter.read(Date.from(day.atStartOfDay(ZoneId.of("Pacific/Tongatapu")).toInstant()), null)).isEqualTo(day.minusDays(1));
        // The same zone at two offsets: Auckland reads right at +12:00 (July) and a day early at +13:00 (January).
        assertThat(converter.read(Date.from(LocalDate.parse("2026-07-01").atStartOfDay(ZoneId.of("Pacific/Auckland")).toInstant()), null))
                .isEqualTo(LocalDate.parse("2026-07-01"));
        assertThat(converter.read(Date.from(day.atStartOfDay(ZoneId.of("Pacific/Auckland")).toInstant()), null)).isEqualTo(day.minusDays(1));
    }

    @Test void INC_68_round2_aDayStringIsReadAndWrittenAsItIs() {
        assertThat(converter.read("2026-02-28", null)).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(converter.write(LocalDate.parse("2026-02-28"), null)).isEqualTo("2026-02-28");
    }
}
