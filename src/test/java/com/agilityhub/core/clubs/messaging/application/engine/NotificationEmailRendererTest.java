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
        var news = renderer.render(notification("N-24", NotificationCategory.CLUB_NEWS, "ca", null, null), "laura@example.test", SETTINGS, "https://app.example.test",
                link, Map.of("clubId", "club-a", "notificationId", "notification-a"));
        assertThat(news.headers()).containsExactly(Map.entry("List-Unsubscribe", "<" + link + ">"));
        assertThat(news.html()).contains("Deixar de rebre aquests comunicats", link.replace("&", "&amp;"), "Respon a aquest correu per contactar amb el club.");
        assertThat(news.text()).contains("Deixar de rebre aquests comunicats: " + link);
        // A PERSONAL (or any transactional) mail never carries it, even when a link is offered.
        for (var category : List.of(NotificationCategory.PERSONAL, NotificationCategory.OPERATIONAL, NotificationCategory.CLUB_CHANGES)) {
            var personal = renderer.render(notification("N-09", category, "ca", null, null), "laura@example.test", SETTINGS, "https://app.example.test", link, Map.of());
            assertThat(personal.headers()).isEmpty(); assertThat(personal.html()).doesNotContain("Deixar de rebre", "baixa"); assertThat(personal.text()).doesNotContain("baixa");
        }
        // A link that is not https (or carries credentials) is never written.
        var unsafe = renderer.render(notification("N-24", NotificationCategory.CLUB_NEWS, "ca", null, null), "laura@example.test", SETTINGS, "https://app.example.test",
                "http://app.example.test/x", Map.of());
        assertThat(unsafe.headers()).isEmpty();
        assertThat(renderer.render(notification("N-24", NotificationCategory.CLUB_NEWS, "ca", null, null), "laura@example.test", SETTINGS, null, null, Map.of()).headers()).isEmpty();
    }

    @Test void R_11_08_subjectLayoutSenderAndTheActionLinkInTheRecipientsLanguage() {
        var mail = renderer.render(notification("N-08a", NotificationCategory.CLUB_CHANGES, "es", NotificationActionType.CHANGE_CLASS,
                Map.of("calendar_links", "https://app.example.test/cal.ics")), "laura@example.test", SETTINGS, "https://app.example.test", null, Map.of("clubId", "club-a"));
        assertThat(mail.to()).isEqualTo("laura@example.test"); assertThat(mail.subject()).isEqualTo("Classe anul·lada pel club");
        assertThat(mail.from()).isEqualTo(new EmailMessage.Address("exemple@mail.example.test", "Club Agility Exemple")); assertThat(mail.replyTo()).isEqualTo("club@example.test");
        assertThat(mail.locale()).isEqualTo(Locale.forLanguageTag("es")); assertThat(mail.tags()).containsEntry("clubId", "club-a");
        assertThat(mail.html()).contains("lang=\"es\"", "Club Agility Exemple", "https://assets.example.test/logo.svg", "#3155A4", "Dimecres 12 · 18:50 · B+C.",
                "Aquesta sessió no compta al teu còmput.", "https://app.example.test/notificacions", "Abre la app", "https://app.example.test/cal.ics", "Añádela al calendario")
                .doesNotContain("[[", " th:", "Dejar de recibir");
        assertThat(mail.text()).startsWith("Classe anul·lada pel club\n\nDimecres 12").contains("Abre la app: https://app.example.test/notificacions",
                "Añádela al calendario: https://app.example.test/cal.ics", "Club Agility Exemple\nResponde a este correo").doesNotContain("<p>");
        // No verified app domain: no links; an unsafe logo, a bad colour and a non-https calendar are dropped; no locale → the club's.
        var bare = renderer.render(notification("N-04", NotificationCategory.OPERATIONAL, null, NotificationActionType.OPEN_BOOKING, Map.of("calendar_links", "javascript:x")),
                "laura@example.test", new ClubEmailSettings.Settings("Club", "http://logo.example.test/x.svg", "red", null, "a@example.test", "Club", null, "en", 15), null,
                null, Map.of());
        assertThat(bare.html()).doesNotContain("/notificacions", "javascript:", "logo.example.test", "background:red", "<img").contains("lang=\"en\"");
        assertThat(bare.locale()).isEqualTo(Locale.ENGLISH); assertThat(bare.replyTo()).isNull();
        var noBody = new Notification("n", "club-a", "N-04", NotificationCategory.OPERATIONAL, null, null, null, null, "d", NotificationAudience.MEMBER, null, "ca", null,
                TemplateIcon.check, TemplateColor.OK, "Reserva confirmada", null, null, null, List.of(), null, NOW, null, null, null, null, null, null, null, null, null);
        assertThat(renderer.render(noBody, "laura@example.test", SETTINGS, null, null, Map.of()).text()).startsWith("Reserva confirmada\n\n\n\nClub Agility Exemple");
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
