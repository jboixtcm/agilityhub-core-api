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
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * S11 R-11-05 `[[var]]` values, already formatted for one recipient: the recipient's locale and the club's time zone
 * (`ClubFormats`), «avui/demà/ahir» against the club-local date of the injected `Clock`. The form follows the variable:
 * `class_date` → relative, else «dimecres 12» (with the month beyond six days); `date` → relative, else «dt 4»;
 * `review_day` → relative, else the weekday; `class_time`, `confirm_by`, `review_time` → «18:50»; `time` → «8:00–8:30»;
 * `effective_date`, `week_start`, `requested_date`, `execute_date` → the long date; `from_month`, `to_month`, `month`,
 * `period` → the month; `pack_expiry` → the short date; `gender` → lower case (`female`, `male`, `other`, the ICU keys of
 * S11 §8/§10); money → `fmtMoney`; a `LocalizedText` → its text in the locale
 * (R-11-01 fallback); `changes` and `dogs` → their lists. Numbers stay numbers (ICU `plural`), booleans stay booleans and
 * enums become their names (ICU `select`).
 */
public final class VariableFormatter {
    private final ClubFormats formats; private final IcuMessageSource messages; private final Clock clock; private final String clubDefaultLocale;

    public VariableFormatter(ClubFormats formats, IcuMessageSource messages, Clock clock, String clubDefaultLocale) {
        this.formats = formats; this.messages = messages; this.clock = clock; this.clubDefaultLocale = clubDefaultLocale;
    }

    public Map<String, Object> format(Map<String, ?> raw, Locale locale) {
        var formatted = new LinkedHashMap<String, Object>();
        raw.forEach((key, value) -> { if (value != null) { formatted.put(key, format(key, value, locale)); } });
        return formatted;
    }

    public Object format(String key, Object value, Locale locale) {
        LocalDate today = formats.localDate(clock.instant());
        return switch (key) {
            case "class_date" -> value instanceof NotificationValues.AbsoluteDate absolute ? formats.formatFullDate(absolute.day(), locale)
                    : day(value) == null ? text(value, locale) : formats.formatDay(day(value), today, locale);
            case "date" -> day(value) == null ? text(value, locale) : formats.formatShortDay(day(value), today, locale);
            case "review_day" -> day(value) == null ? text(value, locale) : formats.formatWeekday(day(value), today, locale);
            case "class_time", "confirm_by", "review_time" -> clock(value, locale);
            case "time" -> value instanceof NotificationValues.TimeRange range
                    ? formats.formatClock(range.start()) + "–" + formats.formatClock(range.end()) : clock(value, locale);
            case "effective_date", "week_start", "requested_date", "execute_date" -> day(value) == null ? text(value, locale) : formats.formatLongDate(day(value), locale);
            case "from_month", "to_month", "month", "period" -> month(value) == null ? text(value, locale) : formats.formatMonth(month(value), locale);
            case "pack_expiry" -> day(value) == null ? text(value, locale) : formats.formatShortDate(day(value), locale);
            // S11 §10: the ICU `select` keys of `gender` are lower case (`female`, `male`, `other`: «OTHER → other»), as S11 §8's N-02.
            case "gender" -> text(value, locale).toLowerCase(Locale.ROOT);
            // Numbers (ICU `plural`) and booleans (ICU `select` reads «true»/«false») keep their type.
            default -> value instanceof Number || value instanceof Boolean ? value : text(value, locale);
        };
    }

    /** Any value as text in the locale (numbers too). */
    public String text(Object value, Locale locale) {
        if (value == null) { return ""; }
        if (value instanceof String text) { return text; }
        if (value instanceof LocalizedText localized) { return localized(localized, locale); }
        if (value instanceof NotificationValues.Localized localized) { return localized.in(locale); }
        if (value instanceof Money money) { return formats.formatMoney(money, locale); }
        if (value instanceof NotificationValues.AbsoluteDate absolute) { return formats.formatFullDate(absolute.day(), locale); }
        if (value instanceof NotificationValues.TimeRange range) { return formats.formatClock(range.start()) + "–" + formats.formatClock(range.end()); }
        if (value instanceof NotificationValues.Changes changes) {
            return changes.items().stream().map(change -> messages.format(change.messageKey(), format(change.arguments(), locale), locale))
                    .collect(Collectors.joining(" · "));
        }
        if (value instanceof NotificationValues.DogLabels dogs) {
            return dogs.items().stream().map(dog -> {
                String level = dog.level() == null ? "" : text(dog.level(), locale);
                return level.isBlank() ? Objects.toString(dog.name(), "") : dog.name() + " (" + level + ")";
            }).collect(Collectors.joining(", "));
        }
        if (value instanceof LocalDate day) { return formats.formatFullDate(day, locale); }
        if (value instanceof Instant instant) { return formats.formatDateTime(instant, locale); }
        if (value instanceof LocalTime time) { return formats.formatClock(time); }
        if (value instanceof YearMonth month) { return formats.formatMonth(month, locale); }
        if (value instanceof Enum<?> constant) { return constant.name(); }
        if (value instanceof List<?> list) { return list.stream().map(item -> text(item, locale)).collect(Collectors.joining(", ")); }
        return value.toString();
    }

    /** R-11-01 per field: the recipient's language, else the club's default, else the text's own default. */
    public String localized(LocalizedText text, Locale locale) {
        if (text == null) { return ""; }
        String exact = text.values().get(locale.getLanguage());
        if (exact != null && !exact.isBlank()) { return exact; }
        String club = clubDefaultLocale == null ? null : text.values().get(clubDefaultLocale);
        if (club != null && !club.isBlank()) { return club; }
        return Objects.toString(text.resolve(locale).value(), "");
    }

    private String clock(Object value, Locale locale) {
        if (value instanceof Instant instant) { return formats.formatClock(instant); }
        if (value instanceof LocalTime time) { return formats.formatClock(time); }
        if (value instanceof String text) {
            try { return formats.formatClock(LocalTime.parse(text)); } catch (DateTimeParseException notATime) { return text; }
        }
        return text(value, locale);
    }
    private LocalDate day(Object value) {
        if (value instanceof LocalDate day) { return day; }
        if (value instanceof Instant instant) { return formats.localDate(instant); }
        if (value instanceof NotificationValues.AbsoluteDate absolute) { return absolute.day(); }
        if (value instanceof String text) { try { return LocalDate.parse(text); } catch (DateTimeParseException notADate) { return null; } }
        return null;
    }
    private YearMonth month(Object value) {
        if (value instanceof YearMonth month) { return month; }
        if (value instanceof LocalDate day) { return YearMonth.from(day); }
        if (value instanceof Instant instant) { return YearMonth.from(formats.localDate(instant)); }
        if (value instanceof String text) {
            try { return YearMonth.parse(text.length() > 7 ? text.substring(0, 7) : text); } catch (DateTimeParseException notAMonth) { return null; }
        }
        return null;
    }
}
