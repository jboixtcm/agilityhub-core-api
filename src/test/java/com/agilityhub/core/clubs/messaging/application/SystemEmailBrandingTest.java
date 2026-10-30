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

    private SystemEmailRenderer renderer(String brand) throws java.io.IOException {
        var resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/"); resolver.setSuffix(".html"); resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        var engine = new TemplateEngine(); engine.setTemplateResolver(resolver);
        return new SystemEmailRenderer(new IcuMessageSource(), engine, brand);
    }
}
