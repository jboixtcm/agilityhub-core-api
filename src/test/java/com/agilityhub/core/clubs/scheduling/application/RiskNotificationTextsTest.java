package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.messaging.application.engine.TemplateRenderer;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * S15 §8 N-16: the registrants' copy names the dog; the admins' copy (`notif.N-16.staff.*`, E7-T02) has no dog phrase, in
 * ca/es/en, rendered as the S11 engine renders them.
 */
class RiskNotificationTextsTest {
    private final IcuMessageSource messages; { try { messages = new IcuMessageSource(); } catch (java.io.IOException bundles) { throw new java.io.UncheckedIOException(bundles); } }
    private final TemplateRenderer renderer = new TemplateRenderer();

    Map<String, Object> variables(boolean member, String autoCancel) {
        var values = new LinkedHashMap<String, Object>();
        values.put("club_name", "Club Example"); values.put("class_date", "dijous 8"); values.put("class_time", "20:00");
        values.put("class_description", "Iniciació"); values.put("review_time", "7:30"); values.put("review_day", "dijous");
        values.put("auto_cancel", autoCancel);
        if (member) { values.put("dog_name", "Nit"); }
        return values;
    }
    /** E7-T03: the member's copy is the club template's seed (`seed/message-templates.{locale}.json`); the staff copy stays in `messages_*`. */
    private final com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed seed =
            com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed.load();
    String render(String key, Map<String, Object> variables, Locale locale) {
        String pattern = key.equals("notif.N-16.body") ? seed.of("N-16").orElseThrow().body().get(locale.getLanguage()) : messages.patternIn(key, locale);
        return renderer.text("N-16", pattern, variables, locale, variables.keySet());
    }

    @Test void T_15_13_n16StaffAndMemberTextsInTheThreeLocales() {
        Map<String, List<String>> expected = Map.of(
                "ca", List.of("dijous 8 · 20:00 · Iniciació, amb Nit", "Club Example: dijous 8 · 20:00 · Iniciació", "caldrà decidir", "el club decidirà", "s'hi apunta"),
                "es", List.of("dijous 8 · 20:00 · Iniciació, con Nit", "Club Example: dijous 8 · 20:00 · Iniciació", "habrá que decidir", "el club decidirá", "se apunta"),
                "en", List.of("dijous 8 · 20:00 · Iniciació, with Nit", "Club Example: dijous 8 · 20:00 · Iniciació", "you will have to decide", "the club will decide", "books"));
        expected.forEach((tag, texts) -> {
            var locale = Locale.forLanguageTag(tag);
            for (String autoCancel : List.of("true", "false")) {
                String member = render("notif.N-16.body", variables(true, autoCancel), locale);
                String staff = render("notif.N-16.staff.body", variables(false, autoCancel), locale);
                assertThat(member).as("%s member", tag).startsWith(texts.get(0)).contains("7:30").doesNotContain("{");
                assertThat(staff).as("%s staff", tag).startsWith(texts.get(1)).contains("7:30")
                        .doesNotContain("Nit").doesNotContain("  ").doesNotContain("{");
                if (autoCancel.equals("false")) { assertThat(staff).contains(texts.get(2)); assertThat(member).contains(texts.get(3)); }
                else { assertThat(staff).doesNotContain(texts.get(2)).contains(texts.get(4)); assertThat(member).contains(texts.get(4)); }
            }
        });
    }
}
