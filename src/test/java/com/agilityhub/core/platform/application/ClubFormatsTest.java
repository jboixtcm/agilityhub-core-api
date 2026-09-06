package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.Money;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;

class ClubFormatsTest {
    private final IcuMessageSource messages = new IcuMessageSource();
    ClubFormatsTest() throws java.io.IOException { }

    @ParameterizedTest @CsvSource({"ca,abans", "es,antes", "en,before"})
    void T_02_17_durationFormatsUseRecipientLanguage(String language, String before) {
        var formats = formats("Europe/Madrid");
        Locale locale = Locale.forLanguageTag(language);
        try (var recipient = LocaleContext.open(locale)) {
            assertThat(formats.formatDuration(Duration.ofHours(2))).isEqualTo("2 h");
            assertThat(formats.formatDuration(Duration.ofMinutes(30))).isEqualTo("30 min");
            assertThat(formats.formatDuration(Duration.ofHours(4), ClubFormats.DurationStyle.BEFORE))
                    .isEqualTo("4 h " + before);
            assertThat(formats.formatDuration(Duration.ofMinutes(90))).isEqualTo("1 h 30 min");
            assertThat(formats.formatDuration(Duration.ZERO)).isEqualTo("0 min");
            assertThat(formats.formatDuration(Duration.ofHours(48))).isEqualTo("48 h");
            assertThat(formats.formatDuration(Duration.ofSeconds(90))).isEqualTo("90 s");
            assertThat(formats.formatDuration(Duration.ofNanos(1))).isEqualTo(language.equals("en") ? "0.000000001 s" : "0,000000001 s");
            assertThat(formats.formatDuration(Duration.ofMillis(500))).isEqualTo(language.equals("en") ? "0.5 s" : "0,5 s");
        }
        assertThatThrownBy(() -> formats.formatDuration(Duration.ofSeconds(-1), locale))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
        ca | 2/1/30 | 0:30 | 45,00 €
        es | 2/1/30 | 0:30 | 45,00 €
        en | 1/2/30 | 12:30 AM | €45.00
        """)
    void E0_T08_datesTimesAndMoneyUseTheRecipientLocale(String language, String date, String time, String money) {
        var formats = formats("Europe/Madrid");
        Instant instant = Instant.parse("2030-01-01T23:30:00Z");
        try (var recipient = LocaleContext.open(Locale.forLanguageTag(language))) {
            assertThat(spaces(formats.formatDate(instant))).isEqualTo(date);
            assertThat(spaces(formats.formatTime(instant))).isEqualTo(time);
            assertThat(spaces(formats.formatDateTime(instant))).contains(date, time);
            assertThat(spaces(formats.formatMoney(new Money(4500, "EUR")))).isEqualTo(money);
            assertThat(formats.formatMoney(new Money(123, "JPY"))).doesNotContain(".00", ",00");
        }
        assertThat(formats.formatMoney(new Money(Long.MAX_VALUE, "EUR"), Locale.ENGLISH))
                .contains("92,233,720,368,547,758.07");
    }
    @Test void E0_T08_timeZoneAndDstUseTheClubAndNeverTheDevice() {
        var madrid = formats("Europe/Madrid");
        var buenosAires = formats("America/Argentina/Buenos_Aires");
        Locale ca = Locale.forLanguageTag("ca");
        Instant midnight = Instant.parse("2030-01-01T23:30:00Z");
        assertThat(madrid.formatDate(midnight, ca)).isEqualTo("2/1/30");
        assertThat(buenosAires.formatDate(midnight, ca)).isEqualTo("1/1/30");
        assertThat(buenosAires.formatTime(midnight, ca)).isEqualTo("20:30");
        Instant before = Instant.parse("2030-03-31T00:30:00Z");
        Instant after = Instant.parse("2030-03-31T01:30:00Z");
        assertThat(madrid.formatTime(before, ca)).isEqualTo("1:30");
        assertThat(madrid.formatTime(after, ca)).isEqualTo("3:30");
        assertThat(buenosAires.formatTime(before, ca)).isEqualTo("21:30");
        assertThat(buenosAires.formatTime(after, ca)).isEqualTo("22:30");
        assertThat(spaces(madrid.formatDateTime(midnight, Locale.ENGLISH))).contains("1/2/30", "12:30 AM");
    }
    private ClubFormats formats(String zone) {
        var club = new ClubConfig.ClubView("club-a", "club-a", "Example Club", List.of("ca", "es", "en"), "ca",
                zone, "EUR", null, null, "ACTIVE", "https://example.test/privacy");
        return new ClubFormats(new ClubConfig(club, Map.of(), Set.of(), null, Map.of()), messages);
    }
    private String spaces(String text) { return text.replace('\u00a0', ' ').replace('\u202f', ' '); }
}
