package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationCatalog;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import static org.assertj.core.api.Assertions.*;

/**
 * T-11-12 (R-11-12, R-11-05, R-11-06), the D9 validation of a template save, each failure alone and with the status of
 * CATALEG_ERRORS rule 0: a cell outside the caps → `CHANNEL_NOT_ALLOWED` (422, `details {audience, channel}`); a template
 * without its required variable (N-08a `admin_text`; N-02's `link` is its welcome e-mail's only since E76) →
 * `VALIDATION_ERROR` with `details.missingVariables` (S11's `TEMPLATE_MISSING_VARIABLE` is no catalog code, E66); unbalanced
 * braces → `TEMPLATE_SYNTAX_ERROR`; a mandatory template disabled → `TEMPLATE_MANDATORY` (422); plus the unknown variable
 * (`details.field`), the SMS rules and the field rules. The reset of T-11-12 is `MessageTemplatesIT`.
 */
class TemplateValidatorTest {
    private final TemplateValidator validator = new TemplateValidator();
    private final MessageTemplateSeed seed = MessageTemplateSeed.load();
    private final TemplateSampleData samples;
    private final ClubConfig config;
    TemplateValidatorTest() throws Exception {
        samples = new TemplateSampleData(new IcuMessageSource());
        config = new ClubConfig(new ClubConfig.ClubView("club", "club", "Club Agility Exemple", List.of("ca", "es"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null),
                Map.of(), Set.of(), null, Map.of());
    }

    private TemplateValidator.Rules rules(String code) {
        var spec = NotificationCatalog.byCode(code).orElseThrow();
        var icu = new HashSet<String>(); // the seed's selectors, as `MessageTemplateService.seedArguments` (E7-T06)
        var seeded = seed.of(code).orElseThrow();
        for (var texts : List.of(seeded.title(), seeded.body(), seeded.smsBody())) { texts.values().forEach(t -> icu.addAll(TemplateValidator.syntax(code, t).selectors())); }
        var caps = new EnumMap<NotificationAudience, Set<NotificationChannel>>(NotificationAudience.class);
        spec.audiences().stream().filter(NotificationAudience::templated).forEach(a -> caps.put(a, spec.caps(a)));
        return new TemplateValidator.Rules(NotificationCatalog.templateVariables(spec), icu, NotificationCatalog.templateRequiredVariables(spec), caps,
                MessageTemplateSeed.smsCapable(spec), spec.mandatory());
    }
    /** The seed of `code` in ca/es with the given replacements. */
    private TemplateValidator.Draft draft(String code, Map<String, String> title, Map<String, String> body, Map<String, String> sms,
            Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix, boolean enabled) {
        var seeded = seed.of(code).orElseThrow();
        return new TemplateValidator.Draft(title != null ? title : only(seeded.title()), body != null ? body : only(seeded.body()), sms != null ? sms : only(seeded.smsBody()),
                matrix != null ? matrix : seeded.matrix(), enabled);
    }
    private static Map<String, String> only(Map<String, String> texts) {
        var kept = new LinkedHashMap<String, String>(); texts.forEach((l, t) -> { if (!l.equals("en")) { kept.put(l, t); } }); return kept;
    }
    private void validate(String code, TemplateValidator.Draft draft) {
        validator.validate(rules(code), draft, List.of("ca", "es"), "ca", locale -> samples.values(config, locale), true);
    }
    private static ApiException error(Executable call, ErrorCode code) {
        var failure = catchThrowableOfType(() -> { try { call.execute(); } catch (Throwable t) { throw (Exception) t; } }, ApiException.class);
        assertThat(failure).as("expected " + code).isNotNull();
        assertThat(failure.code()).isEqualTo(code);
        return failure;
    }
    private static Map<NotificationAudience, Map<NotificationChannel, Boolean>> member(boolean app, boolean email, boolean sms) {
        var matrix = new EnumMap<NotificationAudience, Map<NotificationChannel, Boolean>>(NotificationAudience.class);
        matrix.put(NotificationAudience.MEMBER, new EnumMap<>(Map.of(NotificationChannel.APP, app, NotificationChannel.EMAIL, email, NotificationChannel.SMS, sms)));
        return matrix;
    }

    @Test void T_11_12_theSeedOfEveryCodeIsValidAsItIs() {
        for (var seeded : seed.all()) { assertThatCode(() -> validate(seeded.code(), draft(seeded.code(), null, null, null, null, true))).as(seeded.code()).doesNotThrowAnyException(); }
    }

    @Test void T_11_12_aCellOutsideTheCapsIsChannelNotAllowed() {
        // «Canvi de nivell» (N-09, PERSONAL): SMS for the member is outside its caps (R-11-12 example).
        var failure = error(() -> validate("N-09", draft("N-09", null, null, Map.of(), member(true, true, true), true)), ErrorCode.CHANNEL_NOT_ALLOWED);
        assertThat(failure.code().httpStatus()).isEqualTo(422);
        // Round 2 (item 9, E76): D9 reads the refused cell from `details {audience, channel}`; `cells` lists every one.
        assertThat(failure.details()).containsEntry("audience", "MEMBER").containsEntry("channel", "SMS")
                .containsEntry("cells", List.of(Map.of("audience", "MEMBER", "channel", "SMS")));
        // The same cell is allowed on a CLUB_CHANGES code (N-08a).
        assertThatCode(() -> validate("N-08a", draft("N-08a", null, null, null, member(true, true, true), true))).doesNotThrowAnyException();
    }

    /**
     * The required variable of a template missing → `VALIDATION_ERROR` with `details.missingVariables`: N-08a without the
     * admin's text. Round 2 (E76): N-02's template is its APP copy, and `link` is required (and available) only in its welcome
     * e-mail, so N-02 without `[[link]]` is valid now and N-02 with it is an unknown variable (it would render empty).
     */
    @Test void T_11_12_aTemplateWithoutItsRequiredVariableIsAValidationErrorWithTheMissingVariables() {
        var n08a = Map.of("ca", "[[class_date]] · [[class_time]], amb [[dog_name]].");
        var failure = error(() -> validate("N-08a", draft("N-08a", null, n08a, null, null, true)), ErrorCode.VALIDATION_ERROR);
        assertThat(failure.code().httpStatus()).isEqualTo(400);
        assertThat(failure.details()).containsExactly(Map.entry("missingVariables", List.of("admin_text")));
        var body = Map.of("ca", "Ja tens accés a l'app del club.", "es", "Ya tienes acceso a la app del club.");
        assertThatCode(() -> validate("N-02", draft("N-02", null, body, null, null, true))).doesNotThrowAnyException();
        var withLink = Map.of("ca", "Ja tens accés a l'app del club. Entra-hi amb aquest enllaç: [[link]].");
        assertThat(error(() -> validate("N-02", draft("N-02", null, withLink, null, null, true)), ErrorCode.TEMPLATE_UNKNOWN_VARIABLE).details())
                .containsEntry("field", "body.ca").containsEntry("variables", List.of("link"));
        assertThat(NotificationCatalog.templateVariables(NotificationCatalog.byCode("N-02").orElseThrow())).doesNotContain("link");
        assertThat(NotificationCatalog.byCode("N-02").orElseThrow().requiredVariables()).containsExactly("link"); // the catalog row is unchanged
    }

    @Test void T_11_12_unbalancedBracesAreATemplateSyntaxError() {
        for (String broken : List.of("Classe {gender, select, female {Benvinguda} other {Benvingut}", "Hola [[member_first_name]", "Hola {", "Hola }")) {
            var failure = error(() -> validate("N-04", draft("N-04", null, Map.of("ca", broken), null, null, true)), ErrorCode.TEMPLATE_SYNTAX_ERROR);
            assertThat(failure.code().httpStatus()).as(broken).isEqualTo(400);
            assertThat(failure.details()).containsEntry("field", "body.ca");
        }
    }

    @Test void T_11_12_aMandatoryTemplateCannotBeDisabledButAnotherOneCan() {
        for (String mandatory : List.of("N-02", "N-08a", "N-15", "N-17", "N-32c", "N-36")) {
            var failure = error(() -> validate(mandatory, draft(mandatory, null, null, null, null, false)), ErrorCode.TEMPLATE_MANDATORY);
            assertThat(failure.code().httpStatus()).isEqualTo(422);
        }
        assertThatCode(() -> validate("N-04", draft("N-04", null, null, null, null, false))).doesNotThrowAnyException();
    }

    @Test void T_11_12_unknownVariablesAndIcuArgumentsAreRejectedOnSaving() {
        var failure = error(() -> validate("N-04", draft("N-04", null, Map.of("ca", "Hola [[persona_nom]] i {sobrenom}"), null, null, true)), ErrorCode.TEMPLATE_UNKNOWN_VARIABLE);
        // Round 2 (item 9): `details.field` names the text D9 marks, `details.variables` every unknown one.
        assertThat(failure.details()).containsEntry("field", "body.ca").containsEntry("variables", List.of("persona_nom", "sobrenom"));
        var inTheSms = error(() -> validate("N-08a", draft("N-08a", null, null, Map.of("ca", "[[club_name]]: [[admin_text]]", "es", "[[club_name]] {sobrenom}"), null, true)),
                ErrorCode.TEMPLATE_UNKNOWN_VARIABLE);
        assertThat(inTheSms.details()).containsEntry("field", "smsBody.es").containsEntry("variables", List.of("sobrenom"));
        // The code's own selectors (N-29 `active`) and its own variables are fine.
        assertThatCode(() -> validate("N-29", draft("N-29", null, Map.of("ca", "{active, select, false {Hola} other {[[reason]]}}"), null, null, true)))
                .doesNotThrowAnyException();
        // Round 2 (review #2): the member variables of R-11-12 are CUSTOM templates'; a catalog code has only its row's (N-29: `reason`).
        assertThat(error(() -> validate("N-29", draft("N-29", null, Map.of("ca", "Hola [[member_first_name]]: [[reason]]"), null, null, true)),
                ErrorCode.TEMPLATE_UNKNOWN_VARIABLE).details()).containsEntry("variables", List.of("member_first_name"));
    }

    /**
     * E7-T06 step 5 (claude review #3): a seed's simple `{var}` is no second variable list. The names a save accepts besides
     * the row's variables are the seed's `select`/`plural` argument names only: N-08b's `{class_description}` is refused like
     * its `[[var]]` form (it is not in its CATALEG_NOTIFICACIONS row), while N-01's `has_upfront` and N-16's `auto_cancel`
     * selectors stay. Before the fix `seedArguments` read every ICU argument of the seed, and the text was accepted. Round 2
     * (P1, ruling E81): N-32c's row gained `date`, so `{date}` and `[[date]]` are its own variable now.
     */
    @Test void E7_T06_theSeedsSelectorsAreTheOnlyIcuNamesBesidesTheRowsVariables() {
        var names = TemplateValidator.syntax("t", "{mode, select, FIFO {[[a]] {confirm_by}} other {x}} {count, plural, one {#} other {#}} {n, selectordinal, other {#}} {date}");
        assertThat(names.icu()).containsExactly("mode", "confirm_by", "count", "n", "date");
        assertThat(names.selectors()).containsExactly("mode", "count", "n");
        assertThat(names.printed()).containsExactly("confirm_by", "date");
        assertThat(rules("N-01").icuNames()).contains("has_upfront"); assertThat(rules("N-16").icuNames()).contains("auto_cancel");
        assertThat(rules("N-08b").icuNames()).doesNotContain("class_description"); assertThat(rules("N-32c").icuNames()).doesNotContain("date");
        var n08b = error(() -> validate("N-08b", draft("N-08b", null, Map.of("ca", "[[class_date]] · {class_description}, amb [[dog_name]]: [[changes]]."), null, null, true)),
                ErrorCode.TEMPLATE_UNKNOWN_VARIABLE);
        assertThat(n08b.details()).containsEntry("field", "body.ca").containsEntry("variables", List.of("class_description"));
        assertThat(rules("N-32c").variables()).contains("date");
        for (String sms : List.of("[[club_name]]: [[activity_title]] ({date}). [[admin_text]]", "[[club_name]]: [[activity_title]] ([[date]]). [[admin_text]]")) {
            assertThatCode(() -> validate("N-32c", draft("N-32c", null, null, Map.of("ca", sms), null, true))).as(sms).doesNotThrowAnyException();
        }
    }

    /**
     * E7-T06 round 2 (review #6): a seed selector is accepted only where it selects. N-29's `active` and N-01's
     * `has_upfront` choose a branch of their seed and are no variable of their row, so printed alone (`{active}`, which would
     * render the raw `true`) they are unknown, also next to their own select; inside a `select` they stay valid. A row variable
     * that the seed also selects on (N-15's `mode`, N-16's `auto_cancel`) may be printed. Before the fix every seed selector
     * passed in any position.
     */
    @Test void E7_T06_aSeedSelectorIsAcceptedOnlyInASelectPosition() {
        assertThatCode(() -> validate("N-29", draft("N-29", null, Map.of("ca", "{active, select, true {Bloquejat: [[reason]]} other {Desbloquejat}}"), null, null, true)))
                .doesNotThrowAnyException();
        assertThat(error(() -> validate("N-29", draft("N-29", null, Map.of("ca", "Bloqueig: {active}. [[reason]]"), null, null, true)), ErrorCode.TEMPLATE_UNKNOWN_VARIABLE)
                .details()).containsEntry("field", "body.ca").containsEntry("variables", List.of("active"));
        assertThat(error(() -> validate("N-29", draft("N-29", null, Map.of("ca", "{active, select, true {Sí} other {No}} ({active}). [[reason]]"), null, null, true)),
                ErrorCode.TEMPLATE_UNKNOWN_VARIABLE).details()).containsEntry("variables", List.of("active"));
        assertThat(rules("N-01").variables()).doesNotContain("has_upfront"); assertThat(rules("N-01").icuNames()).contains("has_upfront");
        assertThat(error(() -> validate("N-01", draft("N-01", null, Map.of("ca", "[[member_name]]: {has_upfront}"), null, null, true)), ErrorCode.TEMPLATE_UNKNOWN_VARIABLE)
                .details()).containsEntry("variables", List.of("has_upfront"));
        assertThat(rules("N-15").variables()).contains("mode"); assertThat(rules("N-16").variables()).contains("auto_cancel");
        assertThatCode(() -> validate("N-15", draft("N-15", null, Map.of("ca", "[[class_date]] ({mode})", "es", "[[class_date]] ([[mode]])"), null, null, true)))
                .doesNotThrowAnyException();
        assertThat(TemplateValidator.unknown(rules("N-29"), TemplateValidator.syntax("t", "{active, select, true {[[reason]]} other {x}} {0}"))).containsExactly("0");
    }

    @Test void T_11_06_T_11_12_theSmsRules() {
        // An active SMS cell needs its text (round 2, item 9: `details.field` is the default language's SMS text).
        assertThat(error(() -> validate("N-08a", draft("N-08a", null, null, Map.of(), member(true, true, true), true)), ErrorCode.SMS_BODY_REQUIRED).details())
                .containsExactly(Map.entry("field", "smsBody.ca"));
        // Over 160 GSM-7 characters once rendered with the sample data: rejected on saving.
        String longSms = "[[club_name]]: " + "a".repeat(150) + " [[admin_text]]";
        var failure = error(() -> validate("N-08a", draft("N-08a", null, null, Map.of("ca", longSms), null, true)), ErrorCode.SMS_BODY_TOO_LONG);
        assertThat(failure.code().httpStatus()).isEqualTo(400);
        assertThat(failure.details()).containsEntry("field", "smsBody.ca").containsEntry("max", 160);
        // An SMS text on a code that can never send one.
        assertThat(error(() -> validate("N-04", draft("N-04", null, null, Map.of("ca", "Hola"), null, true)), ErrorCode.VALIDATION_ERROR).details())
                .containsEntry("fieldErrors", List.of(Map.of("field", "smsBody", "code", "INVALID_VALUE")));
    }

    @Test void T_11_12_fieldRules() {
        var tooLong = error(() -> validate("N-04", draft("N-04", null, Map.of("ca", "x".repeat(TemplateValidator.BODY_MAX + 1)), null, null, true)), ErrorCode.VALIDATION_ERROR);
        assertThat(tooLong.details()).containsEntry("fieldErrors", List.of(Map.of("field", "body.ca", "code", "INVALID_VALUE")));
        var noDefault = error(() -> validate("N-04", draft("N-04", Map.of("es", "Reserva confirmada"), null, null, null, true)), ErrorCode.VALIDATION_ERROR);
        assertThat(noDefault.details()).containsEntry("fieldErrors", List.of(Map.of("field", "title.ca", "code", "REQUIRED")));
        var foreign = error(() -> validate("N-04", draft("N-04", Map.of("ca", "Reserva", "fr", "Réservation"), null, null, null, true)), ErrorCode.VALIDATION_ERROR);
        assertThat(foreign.details()).containsEntry("fieldErrors", List.of(Map.of("field", "title.fr", "code", "INVALID_VALUE")));
    }
}
