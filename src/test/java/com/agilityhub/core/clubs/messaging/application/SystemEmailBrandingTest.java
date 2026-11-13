package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.platform.application.ClubEmailSettings;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
import static org.assertj.core.api.Assertions.assertThat;

class SystemEmailBrandingTest {
    private static final ClubEmailSettings.Settings SETTINGS = new ClubEmailSettings.Settings(
            "Example Club", null, "#2563eb", "#ffffff", "club@example.test", "Example Club", null, "ca", 15);
    private static final Map<String, Object> VARIABLES = Map.of(
            "link", "https://app.example.test/activacio?t=fictional&purpose=reset", "device", "Example browser",
            "member_first_name", "Laura", "gender", "OTHER", "club_name", "Example Club", "host", "app.example.test",
            "execute_date", "30/10/2026", "inviter_name", "Alex", "brand", "AgilityHub");

    @Test void systemEmailsUseTheConfiguredBrandInEveryLocaleAndIgnoreCallerBrand() throws Exception {
        var renderer = renderer("  ExampleBrand  ");
        for (String tag : new String[]{"ca", "es", "en"}) {
            for (String code : SystemEmailRenderer.CODES) {
                var mail = renderer.render(code, "laura@example.test", Locale.forLanguageTag(tag), VARIABLES, SETTINGS, Map.of());
                assertThat(mail.subject() + mail.html() + mail.text()).doesNotContain("AgilityHub", "{brand}");
                assertThat(mail.html()).contains("ExampleBrand");
                assertThat(mail.text()).contains("ExampleBrand");
            }
        }
        assertThat(renderer.render("N-25", "laura@example.test", Locale.forLanguageTag("ca"), VARIABLES, SETTINGS, Map.of()).subject())
                .isEqualTo("Enllaç per entrar a ExampleBrand");
    }

    @Test void blankOverrideUsesTenantBrandForSubjectActionAndFooter() throws Exception {
        var mail = renderer("").render("N-25", "laura@example.test", Locale.ENGLISH, VARIABLES, SETTINGS, Map.of());
        assertThat(mail.subject()).isEqualTo("Your Example Club sign-in link");
        assertThat(mail.text()).contains("Sign in to Example Club", "your Example Club account").doesNotContain("AgilityHub", "{brand}");
    }

    @Test void manualWelcomeUsesTheClubsIdentityAndActualLinkInAllLanguages() throws Exception {
        var club = new ClubEmailSettings.Settings("Example Club", "/api/v1/public/example/theme/logo", "#123456", "#ffffff",
                "mail@example.test", "Default platform", null, "ca", 15);
        var link = "https://app.example.test/activacio?t=fictional";
        var variables = Map.of("link", link, "variant", "ADMIN_WELCOME", "expires_minutes", 4320);
        for (var locale : new Locale[]{Locale.forLanguageTag("ca"), Locale.forLanguageTag("es"), Locale.ENGLISH}) {
            var message = renderer("Default platform").render("N-27", "member@example.test", locale, variables, club, Map.of());
            assertThat(message.subject()).contains("Example Club");
            assertThat(message.html()).contains("https://app.example.test/api/v1/public/example/theme/logo", "#123456", link);
            assertThat(message.text()).contains(link, "Example Club").doesNotContain("Default platform", "{brand}");
            assertThat(message.from().name()).isEqualTo("Example Club");
            assertThat(message.tags()).containsEntry("variant", "ADMIN_WELCOME");
        }
    }

    /** AUTH-02 (S03 R-03-06b): N-25's `EMAIL_CHANGE` variant asks to confirm the new address, with the link's own lifetime. */
    @Test void AUTH_02_theEmailChangeVariantAsksToConfirmTheNewAddressInEveryLanguageWithItsOwnLifetime() throws Exception {
        var link = "https://app.example.test/activacio?t=fictional&purpose=email_change";
        var variables = Map.of("link", link, "variant", "EMAIL_CHANGE", "expires_minutes", 10080);
        var subjects = Map.of("ca", "Confirma la teva adreça d’accés a Example Club", "es", "Confirma tu dirección de acceso a Example Club",
                "en", "Confirm your Example Club sign-in address");
        for (var entry : subjects.entrySet()) {
            var locale = Locale.forLanguageTag(entry.getKey());
            var message = renderer("").render("N-25", "new@example.test", locale, variables, SETTINGS, Map.of());
            assertThat(message.subject()).isEqualTo(entry.getValue());
            assertThat(message.text()).contains(link).doesNotContain("{brand}", "{");
            var plain = renderer("").render("N-25", "new@example.test", locale, Map.of("link", link), SETTINGS, Map.of());
            assertThat(message.subject()).isNotEqualTo(plain.subject());
            assertThat(message.text()).isNotEqualTo(plain.text());
        }
        // The expiry line follows the link's own lifetime (7 days), not the 15 minutes of a sign-in link.
        var english = renderer("").render("N-25", "new@example.test", Locale.ENGLISH, variables, SETTINGS, Map.of());
        assertThat(english.text()).contains("This link expires in 168 h.");
        assertThat(renderer("").render("N-25", "new@example.test", Locale.ENGLISH, Map.of("link", link), SETTINGS, Map.of()).text())
                .contains("This link expires in 15 min.");
    }

    private SystemEmailRenderer renderer(String brand) throws java.io.IOException {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new TemplateEngine(); engine.setTemplateResolver(resolver);
        return new SystemEmailRenderer(new IcuMessageSource(), engine, brand);
    }
}
