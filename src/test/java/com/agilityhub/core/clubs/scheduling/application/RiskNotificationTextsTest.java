package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.shared.application.IcuMessageSource;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S15 §8 N-16: the registrants' copy names the dog; the admins' copy (`audience = STAFF`) has no dog phrase, in ca/es/en. */
class RiskNotificationTextsTest {
    Map<String, Object> variables(String audience, String autoCancel) {
        var values = new LinkedHashMap<String, Object>();
        values.put("club_name", "Club Example"); values.put("class_date", "dijous 8"); values.put("class_time", "20:00");
        values.put("review_time", "07:30"); values.put("review_day", "dijous"); values.put("auto_cancel", autoCancel); values.put("audience", audience);
        if (audience.equals("MEMBER")) { values.put("dog_name", "Nit"); }
        return values;
    }

    @Test void T_15_13_n16StaffAndMemberTextsInTheThreeLocales() throws Exception {
        var messages = new IcuMessageSource();
        Map<String, List<String>> expected = Map.of(
                "ca", List.of("la classe de Nit del dijous 8 a les 20:00", "la classe del dijous 8 a les 20:00", "caldrà decidir", "el club decidirà"),
                "es", List.of("la clase de Nit del dijous 8 a las 20:00", "la clase del dijous 8 a las 20:00", "habrá que decidir", "el club decidirá"),
                "en", List.of("Nit's class on dijous 8 at 20:00", "the class on dijous 8 at 20:00", "you will have to decide", "the club will decide"));
        expected.forEach((tag, texts) -> {
            var locale = Locale.forLanguageTag(tag);
            for (String autoCancel : List.of("true", "false")) {
                String member = messages.format("notif.N-16.body", variables("MEMBER", autoCancel), locale);
                String staff = messages.format("notif.N-16.body", variables("STAFF", autoCancel), locale);
                assertThat(member).as("%s member", tag).startsWith("Club Example: ").contains(texts.get(0)).contains("07:30").doesNotContain("{");
                assertThat(staff).as("%s staff", tag).startsWith("Club Example: ").contains(texts.get(1)).contains("07:30")
                        .doesNotContain("Nit").doesNotContain("  ").doesNotContain("{");
                if (autoCancel.equals("false")) { assertThat(staff).contains(texts.get(2)); assertThat(member).contains(texts.get(3)); }
                else { assertThat(staff).doesNotContain(texts.get(2)); }
            }
        });
    }
}
