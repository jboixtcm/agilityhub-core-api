package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.jobs.NoShowNoticesJob;
import com.agilityhub.core.clubs.messaging.application.engine.MessageTemplateSeed;
import com.agilityhub.core.clubs.messaging.application.engine.TemplateRenderer;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * S10 §8 / R-10-06 (E6-T04): N-19 renders in the recipient's language with the class's own date written out in full,
 * never «ahir»/«ayer»/«yesterday» (the batch can be days late). Since E7-T02 the engine formats that date
 * (`NotificationValues.AbsoluteDate` → `ClubFormats.formatFullDate`); since E7-T03 the text is the club template's seed, the
 * S11 §8 copy («[[class_date]] no vas poder venir a la classe de [[class_description]]…»), rendered as the engine renders it.
 * Prints the rendered texts (the evidence of three languages).
 */
class NoShowNotificationTextsTest {
    private final IcuMessageSource messages = new IcuMessageSource();
    private final MessageTemplateSeed seed = MessageTemplateSeed.load();
    NoShowNotificationTextsTest() throws java.io.IOException { }
    private String render(String language) {
        var locale = Locale.forLanguageTag(language);
        var formats = new com.agilityhub.core.platform.application.ClubFormats(java.time.ZoneId.of("Europe/Madrid"), messages);
        var variables = Map.<String, Object>of("dog_name", "Mel", "class_date", formats.formatFullDate(LocalDate.of(2026, 10, 12), locale), "class_description", "A+B");
        var n19 = seed.of("N-19").orElseThrow();
        var rendered = new TemplateRenderer().render("N-19", n19.title().get(language), n19.body().get(language), null, variables, locale, variables.keySet(), true);
        String text = rendered.title() + " | " + rendered.body();
        System.out.println("E6-T04 N-19 " + language + ": " + text);
        return text;
    }

    @Test void T_10_26_n19NamesTheClassDateInTheRecipientsLanguageNeverYesterday() {
        assertThat(render("ca")).startsWith("T'hem trobat a faltar | Dilluns, 12").contains("octubre", "no vas poder venir a la classe de A+B").doesNotContain("ahir");
        assertThat(render("es")).startsWith("Te hemos echado de menos | Lunes, 12").contains("octubre", "no pudiste venir a la clase de A+B").doesNotContain("ayer");
        assertThat(render("en")).startsWith("We missed you | On Monday").contains("October", "you couldn't make it to the A+B class").doesNotContain("yesterday");
    }

    @Test void T_15_16_lateCountsClassesOlderThanOneDay() {
        var run = LocalDate.of(2026, 10, 9);
        assertThat(NoShowNoticesJob.late(LocalDate.of(2026, 10, 8), run)).isFalse();
        assertThat(NoShowNoticesJob.late(LocalDate.of(2026, 10, 7), run)).isTrue();
        assertThat(NoShowNoticesJob.late(LocalDate.of(2026, 10, 6), run)).isTrue();
    }
}
