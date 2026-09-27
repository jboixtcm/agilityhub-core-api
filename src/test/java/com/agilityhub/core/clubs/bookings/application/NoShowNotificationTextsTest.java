package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.jobs.NoShowNoticesJob;
import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.shared.application.IcuMessageSource;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * S10 §8 / R-10-06 (E6-T04): N-19 renders in the recipient's language with the class's own date written out in full,
 * never «ahir»/«ayer»/«yesterday» (the batch can be days late). Prints the rendered texts (the evidence of three languages).
 */
class NoShowNotificationTextsTest {
    private final IcuMessageSource messages = new IcuMessageSource();
    NoShowNotificationTextsTest() throws java.io.IOException { }
    private static Attendance on(LocalDate date) {
        return new Attendance("a", "club", "b", "c", date, null, null, "d", "m", null, null, null, null, null, java.util.List.of(), 0L, null, null);
    }
    private String render(String language) {
        var locale = Locale.forLanguageTag(language);
        var variables = Map.<String, Object>of("dog_name", "Mel", "class_date", NoShowNotifications.classDate(on(LocalDate.of(2026, 10, 12)), locale));
        String text = messages.format("notif.N-19.title", variables, locale) + " | " + messages.format("notif.N-19.body", variables, locale);
        System.out.println("E6-T04 N-19 " + language + ": " + text);
        return text;
    }

    @Test void T_10_26_n19NamesTheClassDateInTheRecipientsLanguageNeverYesterday() {
        assertThat(render("ca")).startsWith("T'hem trobat a faltar | No vas poder venir amb Mel a la classe de dilluns, 12").contains("octubre").doesNotContain("ahir");
        assertThat(render("es")).startsWith("Te hemos echado de menos | No pudiste venir con Mel a la clase del lunes, 12").contains("octubre").doesNotContain("ayer");
        assertThat(render("en")).startsWith("We missed you | You couldn't make it with Mel to the class on Monday").contains("October").doesNotContain("yesterday");
    }

    @Test void T_15_16_lateCountsClassesOlderThanOneDay() {
        var run = LocalDate.of(2026, 10, 9);
        assertThat(NoShowNoticesJob.late(LocalDate.of(2026, 10, 8), run)).isFalse();
        assertThat(NoShowNoticesJob.late(LocalDate.of(2026, 10, 7), run)).isTrue();
        assertThat(NoShowNoticesJob.late(LocalDate.of(2026, 10, 6), run)).isTrue();
    }
}
