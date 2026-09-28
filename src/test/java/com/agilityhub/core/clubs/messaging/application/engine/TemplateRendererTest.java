package com.agilityhub.core.clubs.messaging.application.engine;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

/** S11 R-11-05 rendering (T-11-04): ICU with literal Catalan apostrophes, `[[var]]` substitution, the capital letter. */
class TemplateRendererTest {
    private final TemplateRenderer renderer = new TemplateRenderer();
    private static final Locale CA = Locale.forLanguageTag("ca");
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach void capture() { logs.start(); ((Logger) LoggerFactory.getLogger(TemplateRenderer.class)).addAppender(logs); }
    @AfterEach void release() { ((Logger) LoggerFactory.getLogger(TemplateRenderer.class)).detachAppender(logs); }

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

    @Test void T_11_04_genderSelectUnknownVariablesCapitalAndBrokenPatterns() {
        // The census genders (S03 `FEMALE` · `MALE` · `OTHER`) select the product copy's branches.
        String title = "{gender, select, FEMALE {Benvinguda} other {Benvingut}} a [[club_name]], [[member_first_name]]!";
        var known = List.of("gender", "club_name", "member_first_name");
        assertThat(renderer.text("N-02", title, Map.of("gender", "FEMALE", "club_name", "Cànic", "member_first_name", "Laura"), CA, known))
                .isEqualTo("Benvinguda a Cànic, Laura!");
        assertThat(renderer.text("N-02", title, Map.of("gender", "MALE", "club_name", "Cànic", "member_first_name", "Marc"), CA, known)).isEqualTo("Benvingut a Cànic, Marc!");
        assertThat(renderer.text("N-02", title, Map.of("gender", "OTHER", "club_name", "Cànic", "member_first_name", "Àlex"), CA, known)).isEqualTo("Benvingut a Cànic, Àlex!");
        // A variable the code does not have: empty at render time, with a WARN (the 400 belongs to saving, E7-T03); no double space.
        assertThat(renderer.text("N-04", "Classe [[unknown_var]] demà.", Map.of("unknown_var", "leak"), CA, List.of("dog_name"))).isEqualTo("Classe demà.");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).contains("Notification template of N-04 uses the unknown variable unknown_var");
        // A known variable without a value renders empty; `[[ var ]]` with spaces is a variable too; a value is never re-read as a template.
        assertThat(renderer.text("N-04", "A [[ dog_name ]]·[[class_time]]", Map.of("dog_name", "[[class_time]]"), CA, List.of("dog_name", "class_time")))
                .isEqualTo("A [[class_time]]·");
        // A pattern ICU cannot parse (saved before validation) renders as plain text with a WARN, never an error.
        assertThat(renderer.text("N-04", "Classe {unclosed", Map.of(), CA, null)).isEqualTo("Classe {unclosed");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).contains("Notification template of N-04 is not valid ICU; rendered as plain text");
        assertThat(renderer.text("N-04", null, Map.of(), CA, List.of())).isEmpty();
        // An ICU argument without a value is empty too (a select takes `other`, a plural counts 0), never the raw «{change}».
        assertThat(renderer.text("N-37", "{change, select, DogDeactivated {{dog_name} s'ha donat de baixa.} other {{dog_name} ja és del club.}}", Map.of("dog_name", "Duna"),
                CA, List.of())).isEqualTo("Duna ja és del club.");
        assertThat(renderer.text("N-17", "{dogs_count, plural, =0 {cap gos} one {# gos} other {# gossos}} · {club_name}", Map.of(), CA, List.of())).isEqualTo("cap gos ·");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).contains("Notification template of N-37 has no value for change",
                "Notification template of N-17 has no value for dogs_count", "Notification template of N-17 has no value for club_name");
        // An argument inside a branch is filled silently (N-15's `confirm_by` exists in FIFO only), and empty if its branch is taken.
        String offer = "{mode, select, FIFO {Tens fins a les {confirm_by}.} other {La plaça és per a qui confirmi primer.}}";
        assertThat(renderer.text("N-15", offer, Map.of("mode", "ALL_AT_ONCE"), CA, List.of())).isEqualTo("La plaça és per a qui confirmi primer.");
        assertThat(renderer.text("N-15", offer, Map.of("mode", "FIFO"), CA, List.of())).isEqualTo("Tens fins a les .");
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage).noneMatch(message -> message.contains("confirm_by"))
                .contains("Notification template of N-37 has no value for change"); // `change` itself is a top-level argument
        // (4) the first letter of the title and the body in upper case; SMS keeps its own text and is cut (R-11-06).
        var rendered = renderer.render("N-16", "[[class_date]] [[class_time]]", "{class_date} esteu sols. Si ningú més no s'hi apunta…", "  ",
                Map.of("class_date", "demà", "class_time", "9:30"), CA, List.of("class_date", "class_time"), true);
        assertThat(rendered.title()).isEqualTo("Demà 9:30"); assertThat(rendered.body()).startsWith("Demà esteu sols. Si ningú més no s'hi apunta"); assertThat(rendered.sms()).isNull();
        var withSms = renderer.render("N-08a", "t", "b", "{club_name}: " + "x".repeat(200), Map.of("club_name", "Cànic"), CA, List.of(), true);
        assertThat(withSms.sms().text()).hasSize(160).startsWith("Cànic: ").endsWith("..."); // «à» is GSM-7 assertThat(withSms.sms().truncated()).isTrue();
        assertThat(renderer.render("N-08a", "t", "b", null, Map.of(), CA, List.of(), false).sms()).isNull();
        assertThat(TemplateRenderer.capitalize("«ahir no vas…»")).isEqualTo("«Ahir no vas…»");
        assertThat(TemplateRenderer.capitalize("9:30 demà")).isEqualTo("9:30 demà");
        assertThat(TemplateRenderer.capitalize("Ja")).isEqualTo("Ja");
        assertThat(TemplateRenderer.capitalize("…")).isEqualTo("…");
        assertThat(TemplateRenderer.capitalize("")).isEmpty(); assertThat(TemplateRenderer.capitalize(null)).isEmpty();
    }
}
