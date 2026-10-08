package com.agilityhub.core.clubs.messaging.application.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agilityhub.core.clubs.messaging.application.ports.NotificationValues;
import com.agilityhub.core.clubs.messaging.domain.DogNameArticle;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.NotificationSpec;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.platform.application.ClubFormats;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.Money;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

/**
 * T-11-05 (R-11-05, CONVENCIONS_I18N §5): every seeded code (the templated R1 codes of the catalog, their texts from
 * `seed/message-templates.{ca,es,en}.json` since E7-T03) rendered in `ca`, `es` and `en` with the fictional data set of
 * R-11-12's preview («Laura», «Duna» level «C», «dimecres 12 · 18:50 · B+C · Central», the admin's rain text) — title, body,
 * SMS, e-mail subject and plain text, and the SHA-256 of the e-mail HTML — plus the staff texts (`notif.N-xx.staff.*`) of
 * every code with an `INSTRUCTORS` or `ADMINS` audience, against the committed
 * snapshot `src/test/resources/snapshots/notifications/seed-{locale}.txt`. Every text must render without a missing value,
 * a raw placeholder or the forbidden vocabulary. `-Dsnapshots.update=true` rewrites the snapshot (review its diff); the HTML
 * of every mail is written to `target/notification-snapshots/` for inspection.
 */
class NotificationSeedSnapshotTest {
    private static final ZoneId MADRID = ZoneId.of("Europe/Madrid");
    /** Monday 10 August 2026, 10:00 in Madrid: the class of «dimecres 12» is two days away. */
    private static final Instant NOW = Instant.parse("2026-08-10T08:00:00Z");
    private static final Map<String, String> ADMIN_TEXT = Map.of(
            "ca", "La classe queda anul·lada per la pluja. Podeu reservar-ne una altra des de l'app. Disculpeu les molèsties!",
            "es", "La clase queda cancelada por la lluvia. Podéis reservar otra desde la app. ¡Disculpad las molestias!",
            "en", "The class is cancelled because of the rain. You can book another one in the app. Sorry for the inconvenience!");
    /** The fictional subject ids of every native action (the e-mail's deep link, R-11-11, takes the ones its route needs). */
    private static final Map<String, String> ACTION_PARAMS = new java.util.TreeMap<>(Map.of("dogId", "dog-duna", "classSessionId", "class-exemple",
            "bookingId", "booking-exemple", "waitlistEntryId", "entry-exemple", "activityId", "activity-exemple", "taskId", "task-exemple",
            "invoiceId", "invoice-exemple"));
    private final IcuMessageSource messages;
    private final MessageTemplateSeed seeds = MessageTemplateSeed.load();
    private final NotificationEmailRenderer emails;
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    NotificationSeedSnapshotTest() throws Exception {
        messages = new IcuMessageSource();
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML); resolver.setCharacterEncoding("UTF-8");
        var engine = new TemplateEngine(); engine.setTemplateResolver(resolver);
        emails = new NotificationEmailRenderer(messages, engine);
    }
    @BeforeEach void capture() { logs.start(); ((Logger) LoggerFactory.getLogger(TemplateRenderer.class)).addAppender(logs); }
    @AfterEach void release() { ((Logger) LoggerFactory.getLogger(TemplateRenderer.class)).detachAppender(logs); }

    /** The fictional data set: every variable of the catalog, raw as the owners give them. */
    static Map<String, Object> fictional(Locale locale) {
        var classStart = Instant.parse("2026-08-12T16:50:00Z"); // dimecres 12 · 18:50
        var values = new LinkedHashMap<String, Object>();
        values.put("club_name", "Club Agility Exemple"); values.put("member_name", "Laura Serra Puig"); values.put("member_first_name", "Laura");
        values.put("member_last_names", "Serra Puig"); values.put("gender", "FEMALE"); values.put("dog_name", "Duna");
        values.put("dog_name_article", DogNameArticle.of("Duna", "FEMALE", locale));
        values.put("dogs", new NotificationValues.DogLabels(List.of(new NotificationValues.DogLabel("Duna", "C"), new NotificationValues.DogLabel("Rock", "D"))));
        values.put("class_date", classStart); values.put("class_time", classStart); values.put("class_description", "B+C"); values.put("ring_name", "Central");
        values.put("admin_text", ADMIN_TEXT.get(locale.getLanguage()));
        values.put("date", Instant.parse("2026-08-12T06:00:00Z"));
        values.put("time", new NotificationValues.TimeRange(Instant.parse("2026-08-12T06:00:00Z"), Instant.parse("2026-08-12T06:30:00Z")));
        values.put("changes", new NotificationValues.Changes(List.of(new NotificationValues.Change("scheduling.change.startTime", Map.of("before", "18:50", "after", "19:00")),
                new NotificationValues.Change("scheduling.change.ringId", Map.of("before", "Central", "after", "Muntanya")))));
        values.put("level_name", "C"); values.put("level", "C"); values.put("plan_name", "Quota mensual");
        values.put("upfront_total", new Money(4500, "EUR")); values.put("payment_instructions", "Pagament en efectiu a la recepció del club");
        values.put("pay_link", "https://app.example.test/pagament/exemple"); values.put("link", "https://app.example.test/entra/exemple");
        values.put("retry_link", "https://app.example.test/factures/exemple"); values.put("reason", "Documentació pendent");
        values.put("invoice_number", "F-2026-0042"); values.put("amount", new Money(4500, "EUR")); values.put("fee", new NotificationValues.Changes(List.of(new NotificationValues.Change("notif.N-18b.fee",
                Map.of("first", new Money(1500, "EUR"), "following", new Money(1000, "EUR")))))); values.put("open_ended", false);
        values.put("concept", "Quota d'agost"); values.put("pack_remaining", 2); values.put("pack_expiry", LocalDate.parse("2026-08-31"));
        values.put("requested_date", LocalDate.parse("2026-08-31")); values.put("effective_date", LocalDate.parse("2026-08-31"));
        values.put("from_month", YearMonth.parse("2026-09")); values.put("to_month", YearMonth.parse("2026-10")); values.put("decision", "APPROVED");
        values.put("source", "MEMBER"); values.put("cancelled_count", 3); values.put("confirm_by", Instant.parse("2026-08-12T15:00:00Z")); values.put("mode", "FIFO");
        values.put("entityId", "entity-example"); values.put("calendar_links", "https://app.example.test/calendari/exemple.ics");
        values.put("review_time", "07:30"); values.put("review_day", LocalDate.parse("2026-08-12")); values.put("auto_cancel", "true");
        values.put("dogs_count", 1); values.put("instructor_name", "Marta"); values.put("task_excerpt", "Treballar el contacte a la zona de salts");
        values.put("document_type", "Vacuna antiràbica"); values.put("expires_minutes", 15); values.put("setup_kind", "NEW"); values.put("activity_title", "Seminari de canicross");
        values.put("state", "CONFIRMED"); values.put("week_start", LocalDate.parse("2026-08-17")); values.put("count", 3); values.put("oldest_days", 5);
        values.put("actor", "el club"); values.put("change", "BOOKED"); values.put("masked_account", "···· 2231"); values.put("kind", "CLASS"); values.put("late", false);
        values.put("host", "agility.example.test"); values.put("execute_date", LocalDate.parse("2026-09-09")); values.put("period", YearMonth.parse("2026-08"));
        values.put("pending_count", 4); values.put("job_name", "no-show-notices"); values.put("error_count", 2); values.put("month", YearMonth.parse("2026-08"));
        values.put("cap", 1000); values.put("email", "laura@example.test"); values.put("role", "INSTRUCTOR"); values.put("inviter_name", "Josep");
        values.put("expires_days", 7);
        // The selectors some owners add for the product copy (N-01 upfront payment, N-29 block on/off, N-47 with an admin text).
        values.put("has_upfront", "true"); values.put("active", "true"); values.put("has_admin_text", "true");
        return values;
    }
    static List<NotificationSpec> seeded() {
        return NotificationCatalog.specs().stream().filter(spec -> spec.stage() == NotificationSpec.Stage.R1 && spec.templated()).toList();
    }

    @ParameterizedTest @ValueSource(strings = {"ca", "es", "en"})
    void T_11_05_everySeededCodeAndItsStaffTextsRenderAsTheCommittedSnapshot(String language) throws Exception {
        var locale = Locale.forLanguageTag(language);
        var formatter = new VariableFormatter(new ClubFormats(MADRID, messages), messages, Clock.fixed(NOW, ZoneOffset.UTC), "ca");
        var renderer = new TemplateRenderer();
        var settings = new ClubEmailSettings.Settings("Club Agility Exemple", "https://assets.example.test/logo.svg", "#3155A4", "#FFFFFF", "exemple@mail.example.test",
                "Club Agility Exemple", "club@example.test", "ca", 15);
        var out = new StringBuilder("# T-11-05 seed snapshot · ").append(language).append(" · generated by NotificationSeedSnapshotTest (-Dsnapshots.update=true)\n");
        Path htmlDirectory = Path.of("target/notification-snapshots"); Files.createDirectories(htmlDirectory);
        int codes = 0, staff = 0;
        for (var spec : seeded()) {
            codes++;
            var known = NotificationEngine.known(spec); // the engine's list, the one of D9, the save and the preview (E7-T03 round 2)
            for (var audience : spec.audiences()) {
                boolean isStaff = audience == NotificationAudience.INSTRUCTORS || audience == NotificationAudience.ADMINS;
                var raw = fictional(locale); raw.put("audience", isStaff ? "STAFF" : "MEMBER");
                if (!spec.variables().contains("calendar_links")) { raw.remove("calendar_links"); } // only N-04's owner gives it (an e-mail link)
                var variables = formatter.format(raw, locale);
                // E7-T03: the member (and applicant) copy is the club template's seed; the staff copy is product copy in messages_*.
                String prefix = "notif." + spec.code() + (isStaff ? ".staff" : "");
                var seeded = seeds.of(spec.code()).orElseThrow();
                String title = isStaff ? messages.patternIn(prefix + ".title", locale) : seeded.title().get(language);
                String body = isStaff ? messages.patternIn(prefix + ".body", locale) : seeded.body().get(language);
                assertThat(title).as("%s.title in %s", prefix, language).isNotBlank(); assertThat(body).as("%s.body in %s", prefix, language).isNotBlank();
                String sms = isStaff ? null : seeded.smsBody().get(language);
                var rendered = renderer.render(spec.code(), title, body, sms, variables, locale, known, true);
                String label = spec.code() + " " + audience;
                if (isStaff) { staff++; }
                out.append("\n## ").append(label).append('\n').append("title: ").append(rendered.title()).append('\n').append("body: ").append(rendered.body()).append('\n');
                if (rendered.sms() != null) { out.append("sms: ").append(rendered.sms().text()).append(" [").append(rendered.sms().text().length()).append("]\n"); }
                for (String text : List.of(rendered.title(), rendered.body(), rendered.sms() == null ? "" : rendered.sms().text())) {
                    assertThat(text).as(label + " in " + language).doesNotContain("{", "}", "[[", "]]", "null").doesNotContainIgnoringCase("parella");
                }
                if (rendered.sms() != null) { assertThat(rendered.sms().text()).hasSizeLessThanOrEqualTo(160).doesNotContain("http"); }
                // The e-mail of the member audiences (staff mails share the layout).
                if (!isStaff) {
                    var action = spec.action(audience) == null ? null : new Notification.Action(spec.action(audience), ACTION_PARAMS);
                    var notification = new Notification("snapshot-" + spec.code(), "club-a", spec.code(), spec.category(), null, null, null, null, "d", audience, null, language, null,
                            spec.icon(), spec.color(), rendered.title(), rendered.body(), null, action, List.of(), null, NOW, null, null, null, null, null, null, null, null, variables);
                    String unsubscribe = spec.category() == NotificationCategory.CLUB_NEWS ? "https://app.example.test/comunicats/baixa?t=exemple" : null;
                    var mail = emails.render(notification, "laura@example.test", settings, app -> "https://app.example.test", unsubscribe, Map.of());
                    assertThat(mail.subject()).isEqualTo(rendered.title());
                    assertThat(mail.html()).contains("<h1", "Club Agility Exemple").doesNotContain(" th:", "[[", "{");
                    assertThat(mail.text()).startsWith(rendered.title() + "\n\n" + rendered.body());
                    assertThat(mail.headers().containsKey("List-Unsubscribe")).isEqualTo(spec.category() == NotificationCategory.CLUB_NEWS);
                    Files.writeString(htmlDirectory.resolve(spec.code() + "-" + language + ".html"), mail.html());
                    out.append("email.text: ").append(mail.text().replace("\n", "⏎")).append('\n')
                            .append("email.html.sha256: ").append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mail.html().getBytes(StandardCharsets.UTF_8))), 0, 16).append('\n');
                }
            }
        }
        // No text of the product copy lacks a fictional value or uses a variable its code does not have.
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).isEmpty();
        out.append("\n# ").append(codes).append(" codes, ").append(staff).append(" staff texts\n");
        System.out.println("T-11-05 snapshot " + language + ": " + codes + " seeded codes, " + staff + " staff texts");
        Path snapshot = Path.of("src/test/resources/snapshots/notifications/seed-" + language + ".txt");
        if (Boolean.getBoolean("snapshots.update")) { Files.createDirectories(snapshot.getParent()); Files.writeString(snapshot, out.toString()); }
        assertThat(snapshot).as("run once with -Dsnapshots.update=true and review the diff").exists();
        assertThat(out.toString()).isEqualTo(Files.readString(snapshot));
    }
}
