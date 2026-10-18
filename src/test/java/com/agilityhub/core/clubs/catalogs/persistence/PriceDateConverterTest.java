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
 * day that writer meant, for fixed writer zones east and west of UTC, whatever the zone of the JVM that runs the test.
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

    @Test void INC_68_round2_aDayStringIsReadAndWrittenAsItIs() {
        assertThat(converter.read("2026-02-28", null)).isEqualTo(LocalDate.parse("2026-02-28"));
        assertThat(converter.write(LocalDate.parse("2026-02-28"), null)).isEqualTo("2026-02-28");
    }
}
