package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.Money;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Map;

/** Render notification values in the club time zone and an explicit recipient locale. */
public final class ClubFormats {
    public enum DurationStyle { PLAIN, BEFORE }
    private final ZoneId timeZone;
    private final IcuMessageSource messages;

    public ClubFormats(ClubConfig clubConfig, IcuMessageSource messages) {
        this(ZoneId.of(clubConfig.club().timeZone()), messages);
    }

    public ClubFormats(ZoneId timeZone, IcuMessageSource messages) {
        this.timeZone = timeZone; this.messages = messages;
    }

    public String formatDate(Instant instant) { return formatDate(instant, LocaleContext.current()); }
    public String formatDate(Instant instant, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).withZone(timeZone).format(instant);
    }
    public String formatTime(Instant instant) { return formatTime(instant, LocaleContext.current()); }
    public String formatTime(Instant instant, Locale locale) {
        return DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(timeZone).format(instant);
    }
    public String formatDateTime(Instant instant) { return formatDateTime(instant, LocaleContext.current()); }
    public String formatDateTime(Instant instant, Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(timeZone).format(instant);
    }
    public ZoneId zone() { return timeZone; }
    /** The club-local date of an instant. */
    public LocalDate localDate(Instant instant) { return instant.atZone(timeZone).toLocalDate(); }
    /** «18:50», «8:30»: 24-hour clock without a leading zero, the notices' time form (S11 R-11-05). */
    public String formatClock(Instant instant) { return formatClock(instant.atZone(timeZone).toLocalTime()); }
    public String formatClock(LocalTime time) { return time.format(DateTimeFormatter.ofPattern("H:mm", Locale.ROOT)); }
    /**
     * S11 R-11-05 `class_date`: «ahir» · «avui» · «demà» within one day of `today` (club-local), otherwise the weekday and
     * the day («dimecres 12»), with the month when the day is more than six days away («dimecres 12 d’octubre»).
     */
    public String formatDay(LocalDate day, LocalDate today, Locale locale) {
        String relative = relativeDay(day, today, locale);
        if (relative != null) { return relative; }
        boolean far = Math.abs(java.time.temporal.ChronoUnit.DAYS.between(today, day)) > 6;
        return withoutWeekdayComma(skeleton(far ? "MMMMEEEEd" : "EEEEd", day, locale), locale);
    }
    /** S11 R-11-05 `date`: the relative day, otherwise the short weekday and the day («dt 4»). */
    public String formatShortDay(LocalDate day, LocalDate today, Locale locale) {
        String relative = relativeDay(day, today, locale);
        return relative != null ? relative : skeleton("EEEd", day, locale).replace(".", "");
    }
    /** S11 R-11-05 `review_day`: the relative day, otherwise the weekday («dijous»). */
    public String formatWeekday(LocalDate day, LocalDate today, Locale locale) {
        String relative = relativeDay(day, today, locale);
        return relative != null ? relative : skeleton("EEEE", day, locale);
    }
    /** `fmtDate(long)`: «12 d’octubre de 2026» (`effective_date`, `week_start`). */
    public String formatLongDate(LocalDate day, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(day);
    }
    /** The full date with the weekday («dijous, 8 d’octubre de 2026»): N-19's own class date (S10 §8). */
    public String formatFullDate(LocalDate day, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(day);
    }
    /** `fmtDate(short)` of a local date (`pack_expiry`). */
    public String formatShortDate(LocalDate day, Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(day);
    }
    /** `fmtMonth`: «octubre de 2026» (`from_month`, `to_month`). */
    public String formatMonth(java.time.YearMonth month, Locale locale) {
        var format = com.ibm.icu.text.DateFormat.getInstanceForSkeleton("LLLLy", locale);
        format.setTimeZone(com.ibm.icu.util.TimeZone.GMT_ZONE);
        return format.format(java.util.Date.from(month.atDay(1).atStartOfDay().toInstant(java.time.ZoneOffset.UTC)));
    }
    private String relativeDay(LocalDate day, LocalDate today, Locale locale) {
        long offset = java.time.temporal.ChronoUnit.DAYS.between(today, day);
        if (offset < -1 || offset > 1) { return null; }
        return messages.format(offset == 0 ? "format.day.today" : offset == 1 ? "format.day.tomorrow" : "format.day.yesterday", Map.of(), locale);
    }
    private static String skeleton(String skeleton, LocalDate day, Locale locale) {
        var format = com.ibm.icu.text.DateFormat.getInstanceForSkeleton(skeleton, locale);
        format.setTimeZone(com.ibm.icu.util.TimeZone.GMT_ZONE);
        return format.format(java.util.Date.from(day.atStartOfDay().toInstant(java.time.ZoneOffset.UTC)));
    }
    /** CLDR writes «dimecres, 12 d’octubre»; S11 R-11-05 writes the weekday without the comma in `ca` and `es`. */
    private static String withoutWeekdayComma(String text, Locale locale) {
        return "en".equals(locale.getLanguage()) ? text : text.replaceFirst(", ", " ");
    }
    public String formatMoney(Money money) { return formatMoney(money, LocaleContext.current()); }
    public String formatMoney(Money money, Locale locale) { return money.format(locale); }
    public String formatDuration(Duration duration) { return formatDuration(duration, LocaleContext.current()); }
    public String formatDuration(Duration duration, Locale locale) { return formatDuration(duration, DurationStyle.PLAIN, locale); }
    public String formatDuration(Duration duration, DurationStyle style) {
        return formatDuration(duration, style, LocaleContext.current());
    }
    public String formatDuration(Duration duration, DurationStyle style, Locale locale) {
        if (duration.isNegative()) { throw new IllegalArgumentException("Duration must not be negative"); }
        long hours = duration.toHours();
        int minutes = duration.toMinutesPart();
        int seconds = duration.toSecondsPart();
        String value;
        if (duration.getNano() != 0 || seconds != 0) {
            // Keep sub-minute precision; notification thresholds must never silently lose time.
            value = messages.format("format.duration.seconds", Map.of("count", java.math.BigDecimal.valueOf(duration.getSeconds())
                    .add(java.math.BigDecimal.valueOf(duration.getNano(), 9))), locale);
        } else if (hours == 0) {
            value = messages.format("format.duration.minutes", Map.of("count", minutes), locale);
        } else if (minutes == 0) {
            value = messages.format("format.duration.hours", Map.of("count", hours), locale);
        } else {
            value = messages.format("format.duration.hoursMinutes", Map.of("hours", hours, "minutes", minutes), locale);
        }
        return style == DurationStyle.BEFORE ? messages.format("format.duration.before", Map.of("duration", value), locale) : value;
    }
}
