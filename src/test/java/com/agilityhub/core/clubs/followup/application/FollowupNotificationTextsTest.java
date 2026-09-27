package com.agilityhub.core.clubs.followup.application;

import com.agilityhub.core.shared.application.IcuMessageSource;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * S10 §8 (E6-T03 step 8): N-20, N-21 and N-22 render in each recipient's language (ca, es, en) from the variables the
 * consumers send, with «alumne/alumna» by the member's gender. Prints the rendered texts (the evidence of two languages).
 */
class FollowupNotificationTextsTest {
    private final IcuMessageSource messages = new IcuMessageSource();
    FollowupNotificationTextsTest() throws java.io.IOException { }
    private static final Map<String, Object> N20 = Map.of("dog_name", "Duna", "instructor_name", "Estel", "task_excerpt", "Practiqueu el balancí amb calma");
    private static final Map<String, Object> N21 = Map.of("member_name", "Laura Example", "dog_name", "Duna", "task_excerpt", "Practiqueu el balancí", "gender", "FEMALE");
    private static final Map<String, Object> N22 = Map.of("member_name", "Marc Example", "dog_name", "Toby", "gender", "MALE");

    private String render(String code, Map<String, Object> variables, String locale) {
        var tag = Locale.forLanguageTag(locale);
        String text = messages.format("notif." + code + ".title", variables, tag) + " | " + messages.format("notif." + code + ".body", variables, tag);
        System.out.println("E6-T03 " + code + " " + locale + ": " + text);
        return text;
    }

    @Test void T_10_15_n20AndN21RenderInTheRecipientsLanguage() {
        assertThat(render("N-20", N20, "ca")).isEqualTo("Tasca nova per a Duna | Estel t'ha posat una tasca per a Duna: «Practiqueu el balancí amb calma»");
        assertThat(render("N-20", N20, "es")).isEqualTo("Nueva tarea para Duna | Estel te ha puesto una tarea para Duna: «Practiqueu el balancí amb calma»");
        assertThat(render("N-20", N20, "en")).isEqualTo("New task for Duna | Estel set you a task for Duna: “Practiqueu el balancí amb calma”");
        assertThat(render("N-21", N21, "ca")).isEqualTo("Tasca completada | La tasca de Duna (Laura Example, alumna) ja és feta: «Practiqueu el balancí»");
        assertThat(render("N-21", N21, "es")).isEqualTo("Tarea completada | La tarea de Duna (Laura Example, alumna) ya está hecha: «Practiqueu el balancí»");
        assertThat(render("N-21", N21, "en")).isEqualTo("Task completed | The task for Duna (Laura Example, student) is done: “Practiqueu el balancí”");
    }

    @Test void T_10_18_n22NamesTheStudentByGenderInEachLanguage() {
        assertThat(render("N-22", N22, "ca")).isEqualTo("Nota nova de l'alumne | Marc Example (alumne) ha escrit o canviat la nota als instructors de Toby.");
        assertThat(render("N-22", N22, "es")).isEqualTo("Nota nueva del alumno | Marc Example (alumno) ha escrito o cambiado la nota para los instructores sobre Toby.");
        assertThat(render("N-22", Map.of("member_name", "Laura Example", "dog_name", "Duna", "gender", "FEMALE"), "ca"))
                .isEqualTo("Nota nova de l'alumna | Laura Example (alumna) ha escrit o canviat la nota als instructors de Duna.");
        assertThat(render("N-22", N22, "en")).isEqualTo("New note from a student | Marc Example (student) wrote or changed the note to the instructors about Toby.");
    }
}
