package com.agilityhub.core.platform.application;

import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.Money;
import java.time.Duration;
import java.time.Instant;
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
        this.timeZone = ZoneId.of(clubConfig.club().timeZone()); this.messages = messages;
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
