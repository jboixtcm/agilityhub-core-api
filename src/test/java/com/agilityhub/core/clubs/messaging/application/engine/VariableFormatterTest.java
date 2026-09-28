package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.platform.application.ClubFormats;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** E7-T02 step 5: the `[[var]]` values of R-11-05 in the recipient's language and the club's time zone (T-11-04, T-11-15). */
class VariableFormatterTest {
    private static final Locale CA = Locale.forLanguageTag("ca"), ES = Locale.forLanguageTag("es"), EN = Locale.ENGLISH;
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid"), BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");
    /** Wednesday 7 October 2026, 10:00 in Madrid. */
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private final IcuMessageSource messages;
    VariableFormatterTest() throws Exception { messages = new IcuMessageSource(); }

    private VariableFormatter formatter(ZoneId zone, Instant now) {
        return new VariableFormatter(new ClubFormats(zone, messages), messages, Clock.fixed(now, ZoneOffset.UTC), "ca");
    }
    private VariableFormatter madrid() { return formatter(MADRID, NOW); }
    private static Instant at(String local) { return java.time.LocalDateTime.parse(local).atZone(MADRID).toInstant(); }

    @Test void T_11_04_classDateIsRelativeWithinOneDayElseTheWeekdayAndTheMonthBeyondSixDays() {
        var f = madrid();
        assertThat(f.format("class_date", at("2026-10-06T18:50"), CA)).isEqualTo("ahir");
        assertThat(f.format("class_date", at("2026-10-07T18:50"), CA)).isEqualTo("avui");
        assertThat(f.format("class_date", at("2026-10-08T09:30"), CA)).isEqualTo("demà");
        assertThat(f.format("class_date", LocalDate.parse("2026-10-08"), ES)).isEqualTo("mañana");
        assertThat(f.format("class_date", "2026-10-06", EN)).isEqualTo("yesterday");
        assertThat(f.format("class_date", at("2026-10-09T18:50"), CA)).isEqualTo("divendres 9");
        assertThat(f.format("class_date", at("2026-10-13T18:50"), CA)).isEqualTo("dimarts 13");
        assertThat(f.format("class_date", at("2026-10-14T18:50"), CA)).isEqualTo("dimecres 14 d’octubre");
        assertThat(f.format("class_date", at("2026-09-30T18:50"), CA)).isEqualTo("dimecres 30 de setembre");
        assertThat(f.format("class_date", at("2026-10-09T18:50"), ES)).isEqualTo("viernes 9");
        assertThat(f.format("class_date", at("2026-10-14T18:50"), ES)).isEqualTo("miércoles 14 de octubre");
        assertThat(f.format("class_date", at("2026-10-09T18:50"), EN)).isEqualTo("9 Friday"); // CLDR `EEEEd` in English, as the front's Intl
        // N-19's own date (S10 §8, decision E65): always in full, never relative.
        assertThat(f.format("class_date", new NotificationValues.AbsoluteDate(LocalDate.parse("2026-10-06")), CA)).isEqualTo("dimarts, 6 d’octubre de 2026");
        // Something that is not a date is written as it is.
        assertThat(f.format("class_date", "soon", CA)).isEqualTo("soon");
    }

    @Test void T_11_04_dateTimeAndTheOtherFormsOfR_11_05() {
        var f = madrid();
        assertThat(f.format("date", at("2026-10-04T08:00"), CA)).isEqualTo("dg 4");
        assertThat(f.format("date", at("2026-10-13T08:00"), CA)).isEqualTo("dt 13");
        assertThat(f.format("date", at("2026-10-08T08:00"), CA)).isEqualTo("demà");
        assertThat(f.format("date", "tomorrow-ish", CA)).isEqualTo("tomorrow-ish");
        assertThat(f.format("time", new NotificationValues.TimeRange(at("2026-10-08T08:00"), at("2026-10-08T08:30")), CA)).isEqualTo("8:00–8:30");
        assertThat(f.format("class_time", at("2026-10-08T18:50"), CA)).isEqualTo("18:50");
        assertThat(f.format("class_time", LocalTime.parse("09:05"), CA)).isEqualTo("9:05");
        assertThat(f.format("review_time", "07:30", CA)).isEqualTo("7:30");
        assertThat(f.format("confirm_by", "not a time", CA)).isEqualTo("not a time");
        assertThat(f.format("confirm_by", 42, CA)).isEqualTo("42");
        assertThat(f.format("review_day", LocalDate.parse("2026-10-08"), CA)).isEqualTo("demà");
        assertThat(f.format("review_day", LocalDate.parse("2026-10-10"), CA)).isEqualTo("dissabte");
        assertThat(f.format("review_day", 3, CA)).isEqualTo("3");
        assertThat(f.format("effective_date", LocalDate.parse("2026-10-31"), CA)).isEqualTo("31 d’octubre de 2026");
        assertThat(f.format("week_start", "2026-10-12", ES)).isEqualTo("12 de octubre de 2026");
        assertThat(f.format("effective_date", "someday", CA)).isEqualTo("someday");
        assertThat(f.format("from_month", YearMonth.parse("2026-11"), CA)).isEqualTo("novembre del 2026");
        assertThat(f.format("to_month", LocalDate.parse("2027-01-15"), ES)).isEqualTo("enero de 2027");
        assertThat(f.format("month", "2026-10", EN)).isEqualTo("October 2026");
        assertThat(f.format("period", at("2026-12-01T10:00"), EN)).isEqualTo("December 2026");
        assertThat(f.format("month", "2026-10-15T10:00:00Z", CA)).isEqualTo("octubre del 2026");
        assertThat(f.format("month", "never", CA)).isEqualTo("never");
        assertThat(f.format("pack_expiry", LocalDate.parse("2026-12-31"), CA)).isEqualTo("31/12/26");
        assertThat(f.format("pack_expiry", "later", CA)).isEqualTo("later");
        assertThat(f.format("amount", new Money(4500, "EUR"), CA)).asString().contains("45,00").contains("€");
        assertThat(f.format("fee", new Money(4500, "EUR"), EN)).asString().contains("45.00");
    }

    @Test void T_11_04_listsLocalizedTextsAndTypesKeptForIcu() {
        var f = madrid();
        var changes = new NotificationValues.Changes(List.of(new NotificationValues.Change("scheduling.change.startTime", Map.of("before", "18:50", "after", "19:00")),
                new NotificationValues.Change("scheduling.change.ringId", Map.of("before", "Central", "after", "Muntanya"))));
        assertThat(f.format("changes", changes, CA)).isEqualTo("Hora: 18:50 → 19:00 · Pista: Central → Muntanya");
        var dogs = new NotificationValues.DogLabels(List.of(new NotificationValues.DogLabel("Duna", "C"),
                new NotificationValues.DogLabel("Rock", new LocalizedText(Map.of("ca", "D", "es", "D"), "ca")), new NotificationValues.DogLabel("Ares", null)));
        assertThat(f.format("dogs", dogs, CA)).isEqualTo("Duna (C), Rock (D), Ares");
        var level = new LocalizedText(Map.of("ca", "Iniciació", "es", "Iniciación"), "ca");
        assertThat(f.format("level_name", level, ES)).isEqualTo("Iniciación");
        // R-11-01 per field: a language the text lacks falls back to the club's default, then to the text's own.
        assertThat(f.format("level_name", level, EN)).isEqualTo("Iniciació");
        assertThat(formatter(MADRID, NOW).localized(new LocalizedText(Map.of("es", "Solo es"), "es"), EN)).isEqualTo("Solo es");
        assertThat(f.localized(null, CA)).isEmpty();
        assertThat(f.format("class_description", new NotificationValues.Localized(locale -> locale.getLanguage().equals("es") ? "C y sup." : "C i sup."), ES)).isEqualTo("C y sup.");
        assertThat(f.format("count", 3, CA)).isEqualTo(3); assertThat(f.format("late", true, CA)).isEqualTo(true);
        assertThat(f.format("kind", java.time.DayOfWeek.MONDAY, CA)).isEqualTo("MONDAY");
        assertThat(f.format("list", List.of("a", LocalTime.parse("08:00")), CA)).isEqualTo("a, 8:00");
        assertThat(f.format("when", LocalDate.parse("2026-10-06"), CA)).isEqualTo("dimarts, 6 d’octubre de 2026");
        assertThat(f.format("instant", at("2026-10-06T18:50"), CA)).asString().contains("18:50");
        assertThat(f.format("month_value", YearMonth.parse("2026-10"), CA)).isEqualTo("octubre del 2026");
        assertThat(f.format("range", new NotificationValues.TimeRange(at("2026-10-08T08:00"), at("2026-10-08T08:30")), CA)).isEqualTo("8:00–8:30");
        assertThat(f.format("absolute", new NotificationValues.AbsoluteDate(LocalDate.parse("2026-10-06")), ES)).isEqualTo("martes, 6 de octubre de 2026");
        assertThat(f.format("other", new StringBuilder("x"), CA)).isEqualTo("x");
        assertThat(f.text(null, CA)).isEmpty();
        var raw = new LinkedHashMap<String, Object>(); raw.put("club_name", "Club"); raw.put("absent", null);
        assertThat(f.format(raw, CA)).containsExactly(Map.entry("club_name", "Club"));
        assertThatThrownBy(() -> new NotificationValues.TimeRange(null, NOW)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationValues.Change(null, null)).isInstanceOf(NullPointerException.class);
        assertThat(new NotificationValues.Change("k", null).arguments()).isEmpty();
        assertThat(new NotificationValues.Changes(null).items()).isEmpty(); assertThat(new NotificationValues.DogLabels(null).items()).isEmpty();
        assertThatThrownBy(() -> new NotificationValues.AbsoluteDate(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NotificationValues.Localized(null)).isInstanceOf(NullPointerException.class);
        assertThat(new NotificationValues.Localized(locale -> null).in(CA)).isEmpty();
    }

    @Test void T_11_15_theSameInstantIsTodayInOneClubAndTomorrowInTheOther() {
        // 23:30 UTC on Tuesday 6 October: 01:30 on Wednesday in Madrid, 20:30 on Tuesday in Buenos Aires.
        var instant = Instant.parse("2026-10-06T23:30:00Z");
        var classStart = Instant.parse("2026-10-07T21:00:00Z"); // 23:00 in Madrid, 18:00 in Buenos Aires, both on Wednesday
        assertThat(formatter(MADRID, instant).format("class_date", classStart, CA)).isEqualTo("avui");
        assertThat(formatter(BUENOS_AIRES, instant).format("class_date", classStart, CA)).isEqualTo("demà");
        assertThat(formatter(MADRID, instant).format("class_time", classStart, CA)).isEqualTo("23:00");
        assertThat(formatter(BUENOS_AIRES, instant).format("class_time", classStart, ES)).isEqualTo("18:00");
        // The month of a date-time is the club's own month.
        var lastEvening = Instant.parse("2026-10-31T23:30:00Z");
        assertThat(formatter(MADRID, lastEvening).format("month", lastEvening, CA)).isEqualTo("novembre del 2026");
        assertThat(formatter(BUENOS_AIRES, lastEvening).format("month", lastEvening, CA)).isEqualTo("octubre del 2026");
    }
}
