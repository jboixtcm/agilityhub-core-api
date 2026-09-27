package com.agilityhub.core.clubs.messaging.application.engine;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** S11 R-11-05 rendering (T-11-04): ICU with literal Catalan apostrophes, `[[var]]` substitution, the capital letter. */
class TemplateRendererTest {
    private final TemplateRenderer renderer = new TemplateRenderer();
    private static final Locale CA = Locale.forLanguageTag("ca");

    @Test void T_11_04_apostrophesSurviveEveryIcuBranchAndVariablesAreSubstituted() {
        String pattern = "{club_name}: plaça lliure. {mode, select, FIFO {Confirma-la a l'app abans de les {confirm_by}.} other {Entra a l'app per agafar-la.}}";
        assertThat(renderer.text("N-15", pattern, Map.of("club_name", "Club", "mode", "FIFO", "confirm_by", "10:30"), CA, List.of()))
                .isEqualTo("Club: plaça lliure. Confirma-la a l'app abans de les 10:30.");
        assertThat(renderer.text("N-15", pattern, Map.of("club_name", "Club", "mode", "ALL_AT_ONCE"), CA, List.of()))
                .isEqualTo("Club: plaça lliure. Entra a l'app per agafar-la.");
        assertThat(renderer.text("N-02", "Ja tens accés a l'app d'aquest club, [[member_first_name]].", Map.of("member_first_name", "Laura"), CA,
                List.of("member_first_name"))).isEqualTo("Ja tens accés a l'app d'aquest club, Laura.");
        // An apostrophe already escaped for ICU renders once.
        assertThat(renderer.text("N-16", "{dog_name}''s class", Map.of("dog_name", "Duna"), Locale.ENGLISH, List.of())).isEqualTo("Duna's class");
    }
}
