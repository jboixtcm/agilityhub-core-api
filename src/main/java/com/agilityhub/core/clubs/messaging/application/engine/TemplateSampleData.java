package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.clubs.messaging.domain.DogNameArticle;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubFormats;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * R-11-12 «Vista prèvia amb el joc de dades fictícies per idioma»: every catalog variable with a fictional value, formatted
 * as the engine formats a real notice (R-11-05) in the preview's locale and the club's time zone. The texts come from
 * `notif.preview.*` of the locale («Laura», «Duna» level «C», «B+C», «Central», the rain text of the admin); the dates are
 * fixed so that the class reads «dimecres 12 · 18:50» (the club-local Monday 10 August 2026 at 10:00 is «now», the class is
 * two days later); `club_name` is the club's own name. Product defaults only: nothing of a real member.
 */
public final class TemplateSampleData {
    /** «Now» of the fictional data set, club-local: Monday 10 August 2026, 10:00. */
    static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 10, 10, 0);
    private final IcuMessageSource messages;

    public TemplateSampleData(IcuMessageSource messages) { this.messages = messages; }

    /** The formatted values of the data set in `locale` for `config`'s club (its name, time zone and default language). */
    public Map<String, Object> values(ClubConfig config, Locale locale) {
        var zone = ZoneId.of(config.club().timeZone());
        var formats = new ClubFormats(zone, messages);
        var clock = Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        return new VariableFormatter(formats, messages, clock, config.club().defaultLocale()).format(raw(config.club().name(), zone, locale), locale);
    }

    /** The raw values, as the owners give them to the engine. */
    Map<String, Object> raw(String clubName, ZoneId zone, Locale locale) {
        Instant classStart = at(zone, LocalDateTime.of(2026, 8, 12, 18, 50)), trainingStart = at(zone, LocalDateTime.of(2026, 8, 12, 8, 0));
        String firstName = text("memberFirstName", locale), lastNames = text("memberLastNames", locale), dog = text("dogName", locale);
        var values = new LinkedHashMap<String, Object>();
        values.put("club_name", Objects.toString(clubName, ""));
        values.put("member_first_name", firstName); values.put("member_last_names", lastNames); values.put("member_name", firstName + " " + lastNames);
        values.put("gender", "FEMALE"); values.put("dog_name", dog); values.put("dog_name_article", DogNameArticle.of(dog, "FEMALE", locale));
        values.put("dogs", new NotificationValues.DogLabels(List.of(new NotificationValues.DogLabel(dog, text("levelName", locale)),
                new NotificationValues.DogLabel(text("secondDogName", locale), text("secondLevelName", locale)))));
        values.put("class_date", classStart); values.put("class_time", classStart); values.put("class_description", text("classDescription", locale));
        values.put("ring_name", text("ringName", locale)); values.put("admin_text", text("adminText", locale));
        values.put("date", trainingStart); values.put("time", new NotificationValues.TimeRange(trainingStart, trainingStart.plusSeconds(30 * 60)));
        values.put("changes", new NotificationValues.Changes(List.of(
                new NotificationValues.Change("scheduling.change.startTime", Map.of("before", "18:50", "after", "19:00")),
                new NotificationValues.Change("scheduling.change.ringId", Map.of("before", text("ringName", locale), "after", text("classDescription", locale))))));
        values.put("level_name", text("levelName", locale)); values.put("level", text("levelName", locale)); values.put("plan_name", text("planName", locale));
        values.put("upfront_total", new Money(4500, "EUR")); values.put("payment_instructions", text("paymentInstructions", locale));
        values.put("reason", text("reason", locale)); values.put("invoice_number", "F-2026-0042"); values.put("amount", new Money(4500, "EUR"));
        values.put("fee", new Money(1500, "EUR")); values.put("concept", text("concept", locale)); values.put("pack_remaining", 2);
        values.put("pack_expiry", LocalDate.of(2026, 8, 31)); values.put("requested_date", LocalDate.of(2026, 8, 31));
        values.put("effective_date", LocalDate.of(2026, 8, 31)); values.put("from_month", YearMonth.of(2026, 9)); values.put("to_month", YearMonth.of(2026, 10));
        values.put("decision", "APPROVED"); values.put("cancelled_count", 3); values.put("confirm_by", at(zone, LocalDateTime.of(2026, 8, 12, 17, 0)));
        values.put("mode", "ALL_AT_ONCE"); values.put("review_time", "07:30"); values.put("review_day", LocalDate.of(2026, 8, 12)); values.put("auto_cancel", "true");
        values.put("dogs_count", 1); values.put("instructor_name", text("instructorName", locale)); values.put("task_excerpt", text("taskExcerpt", locale));
        values.put("document_type", text("documentType", locale)); values.put("activity_title", text("activityTitle", locale)); values.put("setup_kind", "NEW");
        values.put("state", "ACTIVE"); values.put("week_start", LocalDate.of(2026, 8, 17)); values.put("count", 3); values.put("oldest_days", 5);
        values.put("actor", Objects.toString(messages.patternIn("notif.N-36.actor", locale), "")); values.put("change", "BOOKED");
        values.put("masked_account", "···· 2231"); values.put("kind", "CLASS"); values.put("late", false); values.put("period", YearMonth.of(2026, 8));
        values.put("pending_count", 4); values.put("job_name", text("jobName", locale)); values.put("error_count", 2); values.put("month", YearMonth.of(2026, 8));
        values.put("cap", 1000); values.put("email", text("email", locale)); values.put("expires_days", 7); values.put("expires_minutes", 15);
        values.put("link", "https://app.example.test/entra"); values.put("pay_link", "https://app.example.test/pagament");
        values.put("retry_link", "https://app.example.test/factures"); values.put("audience", "MEMBER"); values.put("source", "MEMBER");
        values.put("entityId", "exemple"); values.put("has_upfront", "true"); values.put("active", "true"); values.put("has_admin_text", "true");
        return values;
    }

    private String text(String key, Locale locale) { return Objects.toString(messages.patternIn("notif.preview." + key, locale), ""); }
    private static Instant at(ZoneId zone, LocalDateTime local) { return local.atZone(zone).toInstant(); }
}
