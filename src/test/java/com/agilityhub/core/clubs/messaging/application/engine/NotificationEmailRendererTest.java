package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.application.EmailMessage;
import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.clubs.messaging.domain.TemplateColor;
import com.agilityhub.core.clubs.messaging.domain.TemplateIcon;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.*;

/**
 * E7-T02 steps 5 and 9: the e-mail of an engine notification (R-11-05 (5), R-11-08) — the club's layout, the plain-text
 * part, `From`/`Reply-To` from the club's settings, and the unsubscribe footer and `List-Unsubscribe` header on `CLUB_NEWS`
 * only (T-11-23) — and the signed 30-day unsubscribe token.
 */
class NotificationEmailRendererTest {
    private static final Instant NOW = Instant.parse("2026-10-07T08:00:00Z");
    private final NotificationEmailRenderer renderer;
    NotificationEmailRendererTest() throws Exception {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML); resolver.setCharacterEncoding("UTF-8");
        var engine = new TemplateEngine(); engine.setTemplateResolver(resolver);
        renderer = new NotificationEmailRenderer(new IcuMessageSource(), engine);
    }
    /** The club's verified app domains: one host for the member app (`clubs`); none (no button). */
    private static final java.util.function.Function<String, String> APP = app -> "clubs".equals(app) ? "https://app.example.test" : null, NO_APP = app -> null;
    private static final ClubEmailSettings.Settings SETTINGS = new ClubEmailSettings.Settings("Club Agility Exemple", "https://assets.example.test/logo.svg", "#3155A4",
            "#FFFFFF", "exemple@mail.example.test", "Club Agility Exemple", "club@example.test", "ca", 15);

    static Notification notification(String code, NotificationCategory category, String locale, NotificationActionType action, Map<String, Object> variables) {
        return new Notification("notification-a", "club-a", code, category, "template-a", 0L, "event-a", "Event", "dedup-a", NotificationAudience.MEMBER,
                new Notification.Recipient("account-laura", "member-laura", null, null, "Laura"), locale, null, TemplateIcon.x, TemplateColor.ERROR,
                "Classe anul·lada pel club", "Dimecres 12 · 18:50 · B+C.\nAquesta sessió no compta al teu còmput.", null,
                action == null ? null : new Notification.Action(action, Map.of("dogId", "dog-duna")), List.of(), null, NOW,
                null, null, null, null, null, null, null, null, variables);
    }

    @Test void T_11_23_clubNewsCarriesTheUnsubscribeFooterAndHeaderAndTransactionalMailsNever() {
        String link = "https://app.example.test/comunicats/baixa?t=token-a";
        var news = renderer.render(notification("N-24", NotificationCategory.CLUB_NEWS, "ca", null, null), "laura@example.test", SETTINGS, APP,
                link, Map.of("clubId", "club-a", "notificationId", "notification-a"));
        assertThat(news.headers()).containsExactly(Map.entry("List-Unsubscribe", "<" + link + ">"));
        assertThat(news.html()).contains("Deixar de rebre aquests comunicats", link.replace("&", "&amp;"), "Respon a aquest correu per contactar amb el club.");
        assertThat(news.text()).contains("Deixar de rebre aquests comunicats: " + link);
        // A PERSONAL (or any transactional) mail never carries it, even when a link is offered.
        for (var category : List.of(NotificationCategory.PERSONAL, NotificationCategory.OPERATIONAL, NotificationCategory.CLUB_CHANGES)) {
            var personal = renderer.render(notification("N-09", category, "ca", null, null), "laura@example.test", SETTINGS, APP, link, Map.of());
            assertThat(personal.headers()).isEmpty(); assertThat(personal.html()).doesNotContain("Deixar de rebre", "baixa"); assertThat(personal.text()).doesNotContain("baixa");
        }
        // A link that is not https (or carries credentials) is never written.
        var unsafe = renderer.render(notification("N-24", NotificationCategory.CLUB_NEWS, "ca", null, null), "laura@example.test", SETTINGS, APP,
                "http://app.example.test/x", Map.of());
        assertThat(unsafe.headers()).isEmpty();
        assertThat(renderer.render(notification("N-24", NotificationCategory.CLUB_NEWS, "ca", null, null), "laura@example.test", SETTINGS, NO_APP, null, Map.of()).headers()).isEmpty();
    }

    @Test void R_11_08_subjectLayoutSenderAndTheActionLinkInTheRecipientsLanguage() {
        var mail = renderer.render(notification("N-08a", NotificationCategory.CLUB_CHANGES, "es", NotificationActionType.CHANGE_CLASS,
                Map.of("calendar_links", "https://app.example.test/cal.ics")), "laura@example.test", SETTINGS, APP, null, Map.of("clubId", "club-a"));
        assertThat(mail.to()).isEqualTo("laura@example.test"); assertThat(mail.subject()).isEqualTo("Classe anul·lada pel club");
        assertThat(mail.from()).isEqualTo(new EmailMessage.Address("exemple@mail.example.test", "Club Agility Exemple")); assertThat(mail.replyTo()).isEqualTo("club@example.test");
        assertThat(mail.locale()).isEqualTo(Locale.forLanguageTag("es")); assertThat(mail.tags()).containsEntry("clubId", "club-a");
        assertThat(mail.html()).contains("lang=\"es\"", "Club Agility Exemple", "https://assets.example.test/logo.svg", "#3155A4", "Dimecres 12 · 18:50 · B+C.",
                "Aquesta sessió no compta al teu còmput.", "https://app.example.test/reservar?dogId=dog-duna", "Abre la app", "https://app.example.test/cal.ics", "Añádela al calendario")
                .doesNotContain("[[", " th:", "Dejar de recibir");
        assertThat(mail.text()).startsWith("Classe anul·lada pel club\n\nDimecres 12").contains("Abre la app: https://app.example.test/reservar?dogId=dog-duna",
                "Añádela al calendario: https://app.example.test/cal.ics", "Club Agility Exemple\nResponde a este correo").doesNotContain("<p>");
        // No verified app domain: no links; an unsafe logo, a bad colour and a non-https calendar are dropped; no locale → the club's.
        var bare = renderer.render(notification("N-04", NotificationCategory.OPERATIONAL, null, NotificationActionType.OPEN_BOOKING, Map.of("calendar_links", "javascript:x")),
                "laura@example.test", new ClubEmailSettings.Settings("Club", "http://logo.example.test/x.svg", "red", null, "a@example.test", "Club", null, "en", 15), NO_APP,
                null, Map.of());
        assertThat(bare.html()).doesNotContain("/notificacions", "javascript:", "logo.example.test", "background:red", "<img").contains("lang=\"en\"");
        assertThat(bare.locale()).isEqualTo(Locale.ENGLISH); assertThat(bare.replyTo()).isNull();
        var noBody = new Notification("n", "club-a", "N-04", NotificationCategory.OPERATIONAL, null, null, null, null, "d", NotificationAudience.MEMBER, null, "ca", null,
                TemplateIcon.check, TemplateColor.OK, "Reserva confirmada", null, null, null, List.of(), null, NOW, null, null, null, null, null, null, null, null, null);
        assertThat(renderer.render(noBody, "laura@example.test", SETTINGS, NO_APP, null, Map.of()).text()).startsWith("Reserva confirmada\n\n\n\nClub Agility Exemple");
    }

    static Notification withAction(String code, NotificationAudience audience, NotificationActionType type, Map<String, String> params) {
        return new Notification("notification-a", "club-a", code, NotificationCategory.OPERATIONAL, "template-a", 0L, "event-a", "Event", "dedup-a", audience,
                new Notification.Recipient("account-laura", "member-laura", null, null, "Laura"), "ca", null, TemplateIcon.bell, TemplateColor.NEUTRAL,
                "Títol", "Cos.", null, new Notification.Action(type, params), List.of(), null, NOW, null, null, null, null, null, null, null, null, Map.of());
    }

    /**
     * Round 2, review #7 (R-11-11 «Correu: l'acció es tradueix en un enllaç profund a la mateixa ruta»): the e-mail's button and
     * its plain-text link open the route of the native action with the action's parameters, as the app's card does, never the
     * bare feed. N-15 → the seat claim (06/29 with the entry, the class and the dog); N-08a → 04 with the dog preselected.
     * Before the fix both linked to `/notificacions`.
     */
    @Test void R_11_11_theEmailsActionIsADeepLinkToTheActionsRouteWithItsParameters() {
        var params = new java.util.LinkedHashMap<String, String>();
        params.put("dogId", "dog-duna"); params.put("classSessionId", "class-a"); params.put("waitlistEntryId", "entry-a");
        String claim = "https://app.example.test/reservar/confirmar?waitlistEntryId=entry-a&classSessionId=class-a&dogId=dog-duna";
        var seat = renderer.render(withAction("N-15", NotificationAudience.MEMBER, NotificationActionType.CLAIM_SEAT, params), "laura@example.test", SETTINGS,
                APP, null, Map.of());
        assertThat(seat.text()).contains("Obre l’app: " + claim).doesNotContain("/notificacions");
        assertThat(seat.html()).contains("href=\"" + claim.replace("&", "&amp;") + "\"").doesNotContain("/notificacions");
        var cancelled = renderer.render(withAction("N-08a", NotificationAudience.MEMBER, NotificationActionType.CHANGE_CLASS, Map.of("dogId", "dog-duna", "classSessionId", "class-a")),
                "laura@example.test", SETTINGS, APP, null, Map.of());
        assertThat(cancelled.text()).contains("Obre l’app: https://app.example.test/reservar?dogId=dog-duna").doesNotContain("/notificacions");
        assertThat(cancelled.html()).contains("href=\"https://app.example.test/reservar?dogId=dog-duna\"");
        // Staff: the administrators' N-51 opens D10 in the back office's own domain; without a verified one, no button.
        java.util.function.Function<String, String> both = app -> "clubs".equals(app) ? "https://app.example.test" : "https://admin.example.test";
        var bounce = withAction("N-51", NotificationAudience.ADMINS, NotificationActionType.OPEN_MEMBER, Map.of("memberId", "member-marc"));
        assertThat(renderer.render(bounce, "admin@example.test", SETTINGS, both, null, Map.of()).text()).contains("Obre l’app: https://admin.example.test/abonats/member-marc");
        var noBackOffice = renderer.render(bounce, "admin@example.test", SETTINGS, APP, null, Map.of());
        assertThat(noBackOffice.html()).doesNotContain("/abonats", "Obre l’app"); assertThat(noBackOffice.text()).doesNotContain("Obre l’app");
        // An instructor's N-17 opens the class (21) in the member app.
        assertThat(renderer.render(withAction("N-17", NotificationAudience.INSTRUCTORS, NotificationActionType.CHANGE_CLASS, Map.of("classSessionId", "class-a")),
                "marta@example.test", SETTINGS, both, null, Map.of()).text()).contains("Obre l’app: https://app.example.test/instructor/classes/class-a");
    }

    @Test void T_11_23_theUnsubscribeTokenIsSignedValidThirtyDaysAndBoundToItsClub() {
        var clock = new MovableClock(NOW);
        var tokens = new UnsubscribeTokens(new byte[32], clock);
        String token = tokens.issue("club-a", "member-laura");
        assertThat(token).doesNotContain("laura@", "member-laura");
        assertThat(tokens.verify(token, "club-a")).isEqualTo(new UnsubscribeTokens.Claim("club-a", "member-laura"));
        assertThatThrownBy(() -> tokens.verify(token, "club-b")).isInstanceOf(ApiException.class).extracting(f -> ((ApiException) f).code()).isEqualTo(ErrorCode.UNSUBSCRIBE_TOKEN_INVALID);
        String[] parts = token.split("\\.");
        for (String tampered : List.of(parts[0] + "." + parts[1] + "." + (Long.parseLong(parts[2]) + 1) + "." + parts[3], token + "x", "a.b.c", "", "!!.!!.1.x")) {
            assertThatThrownBy(() -> tokens.verify(tampered, "club-a")).isInstanceOf(ApiException.class);
        }
        assertThatThrownBy(() -> tokens.verify(null, "club-a")).isInstanceOf(ApiException.class);
        // Another key never verifies it; 30 days later it expired.
        assertThatThrownBy(() -> UnsubscribeTokens.random(clock).verify(token, "club-a")).isInstanceOf(ApiException.class);
        clock.advance(Duration.ofDays(30).minusSeconds(1)); assertThat(tokens.verify(token, "club-a").memberId()).isEqualTo("member-laura");
        clock.advance(Duration.ofSeconds(1)); assertThatThrownBy(() -> tokens.verify(token, "club-a")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> new UnsubscribeTokens(new byte[16], clock)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new UnsubscribeTokens(null, clock)).isInstanceOf(IllegalStateException.class);
    }

    /** A clock the test moves. */
    static final class MovableClock extends Clock {
        private Instant now;
        MovableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
