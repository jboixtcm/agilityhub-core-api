package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.domain.SmsText;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.ibm.icu.text.MessagePattern;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * S11 R-11-12 (and R-11-05, R-11-06), what a D9 save checks, first failure wins, with the statuses of CATALEG_ERRORS rule 0:
 * <ol>
 * <li>the texts' languages are the club's, `title`/`body` have the club's default one, `body` ≤ {@value #BODY_MAX} and
 * `title` ≤ {@value #TITLE_MAX} characters, an SMS text only where an SMS cell may exist → `400 VALIDATION_ERROR`
 * (`details.fieldErrors`);</li>
 * <li>ICU that does not parse, or a `[[` without its `]]` → `400 TEMPLATE_SYNTAX_ERROR` (`details.field`, `body.ca`);</li>
 * <li>a `[[var]]` outside the template's variables (the one list of {@code NotificationCatalog.templateVariables}), or an
 * ICU argument outside them and the seed's own selectors (a selector only in a `select`/`plural` position, {@link #unknown})
 * → `400 TEMPLATE_UNKNOWN_VARIABLE` (`details.field` of the first text with one, `details.variables`);</li>
 * <li>a required variable of the template (N-08a `admin_text`; N-02's `link` is its e-mail's only, E76) missing from a
 * language → `400 VALIDATION_ERROR` with `details.missingVariables` (S11's `TEMPLATE_MISSING_VARIABLE` is not in the
 * catalog, E66);</li>
 * <li>an active cell outside the caps → `422 CHANNEL_NOT_ALLOWED` (`details {audience, channel}` of the first one and every
 * one in `details.cells`; R-11-12's example: SMS on «Canvi de nivell»);</li>
 * <li>an active SMS cell without an SMS text in the club's default language → `400 SMS_BODY_REQUIRED` (`details.field`,
 * `smsBody.ca`);</li>
 * <li>an SMS text over 160 characters once rendered with the preview's data set and transliterated → `400
 * SMS_BODY_TOO_LONG` (`details.field`, `details.max`; the admin's own text is left out: it is cut at sending time, T-11-06);</li>
 * <li>a mandatory template disabled → `422 TEMPLATE_MANDATORY`.</li>
 * </ol>
 * Pure: the caller gives the rules of the template and the sample values of each language.
 */
public final class TemplateValidator {
    public static final int BODY_MAX = 2000, TITLE_MAX = 200;
    static final Pattern VARIABLE = Pattern.compile("\\[\\[\\s*([A-Za-z0-9_]+)\\s*]]");

    /**
     * @param variables    the `[[var]]` the template may use (D9's «Variables:»)
     * @param icuNames     the ICU arguments it may use: the variables and the selectors of its own seed (`has_upfront`, `active`…)
     * @param required     the variables each language must contain
     * @param caps         per matrix row, the channels a cell may activate
     * @param smsPossible  whether the template may carry an SMS text at all
     * @param mandatory    a mandatory template cannot be disabled
     */
    public record Rules(Collection<String> variables, Collection<String> icuNames, Collection<String> required,
            Map<NotificationAudience, Set<NotificationChannel>> caps, boolean smsPossible, boolean mandatory) { }
    /** The texts per language and the matrix of a save. */
    public record Draft(Map<String, String> title, Map<String, String> body, Map<String, String> smsBody,
            Map<NotificationAudience, Map<NotificationChannel, Boolean>> matrix, boolean enabled) { }

    private final TemplateRenderer renderer = new TemplateRenderer();

    /**
     * @param locales       the club's languages; `defaultLocale` one of them
     * @param samples       the formatted preview values of a language (R-11-12)
     * @param transliterate `messaging.sms.transliterateToGsm7`
     */
    public void validate(Rules rules, Draft draft, List<String> locales, String defaultLocale, Function<Locale, Map<String, Object>> samples, boolean transliterate) {
        var errors = new ArrayList<Map<String, Object>>();
        texts("title", draft.title(), locales, defaultLocale, true, TITLE_MAX, errors);
        texts("body", draft.body(), locales, defaultLocale, true, BODY_MAX, errors);
        if (!draft.smsBody().isEmpty() && !rules.smsPossible()) { errors.add(error("smsBody", "INVALID_VALUE")); }
        else { texts("smsBody", draft.smsBody(), locales, defaultLocale, false, BODY_MAX, errors); }
        if (!errors.isEmpty()) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", errors)); }

        var unknown = new LinkedHashSet<String>();
        String unknownField = null;
        for (var field : List.of(Map.entry("title", draft.title()), Map.entry("body", draft.body()), Map.entry("smsBody", draft.smsBody()))) {
            for (var text : field.getValue().entrySet()) {
                String path = field.getKey() + "." + text.getKey();
                int before = unknown.size();
                unknown.addAll(unknown(rules, syntax(path, text.getValue())));
                if (unknownField == null && unknown.size() > before) { unknownField = path; }
            }
        }
        if (!unknown.isEmpty()) { throw new ApiException(ErrorCode.TEMPLATE_UNKNOWN_VARIABLE, Map.of("field", unknownField, "variables", List.copyOf(unknown))); }

        var missing = new LinkedHashSet<String>();
        for (String locale : draft.body().keySet()) {
            var used = new LinkedHashSet<String>();
            for (String text : List.of(draft.title().getOrDefault(locale, ""), draft.body().get(locale))) {
                var names = syntax("body." + locale, text);
                used.addAll(names.variables()); used.addAll(names.icu());
            }
            rules.required().stream().filter(name -> !used.contains(name)).forEach(missing::add);
        }
        if (!missing.isEmpty()) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("missingVariables", List.copyOf(missing))); }

        var cells = new ArrayList<Map<String, Object>>();
        draft.matrix().forEach((audience, row) -> row.forEach((channel, active) -> {
            if (Boolean.TRUE.equals(active) && !rules.caps().getOrDefault(audience, Set.of()).contains(channel)) {
                cells.add(Map.of("audience", audience.name(), "channel", channel.name()));
            }
        }));
        if (!cells.isEmpty()) { throw channelNotAllowed(cells); }

        boolean smsActive = draft.matrix().values().stream().anyMatch(row -> Boolean.TRUE.equals(row.get(NotificationChannel.SMS)));
        if (smsActive && blank(draft.smsBody().get(defaultLocale))) { throw new ApiException(ErrorCode.SMS_BODY_REQUIRED, Map.of("field", "smsBody." + defaultLocale)); }
        for (var entry : draft.smsBody().entrySet()) {
            var locale = Locale.forLanguageTag(entry.getKey());
            var values = new LinkedHashMap<String, Object>(samples.apply(locale));
            values.remove("admin_text");
            String rendered = renderer.text("preview", entry.getValue(), values, locale, rules.variables());
            var sms = SmsText.of(rendered, transliterate);
            if (sms.truncated()) {
                throw new ApiException(ErrorCode.SMS_BODY_TOO_LONG, Map.of("field", "smsBody." + entry.getKey(), "max", SmsText.MAX));
            }
        }
        if (rules.mandatory() && !draft.enabled()) { throw new ApiException(ErrorCode.TEMPLATE_MANDATORY); }
    }

    /**
     * The `[[var]]` names and the ICU argument names of a text; `selectors` are the ICU arguments that choose a branch
     * (`select`, `plural`, `selectordinal`: `gender`, `has_upfront`, `mode`…), `printed` the ones in any other position
     * (`{date}`, `{count, number}`): both subsets of `icu`, and a name used both ways is in both.
     */
    public record Names(Set<String> variables, Set<String> icu, Set<String> selectors, Set<String> printed) { }

    /**
     * The names of a text its rules do not know (R-11-12), in the text's order: a `[[var]]` outside the variables, and an ICU
     * argument that is no variable and no selector of the seed — a seed selector counts only in a `select`/`plural` position,
     * so `{mode}` printed alone is unknown (E7-T06 round 2, review #6). D9's save and its preview read this one rule.
     */
    public static Set<String> unknown(Rules rules, Names names) {
        var unknown = new LinkedHashSet<String>();
        names.variables().stream().filter(name -> !rules.variables().contains(name)).forEach(unknown::add);
        names.icu().stream().filter(name -> !rules.variables().contains(name) && (!rules.icuNames().contains(name) || names.printed().contains(name)))
                .forEach(unknown::add);
        return unknown;
    }

    /** Parses one text as the renderer does (every apostrophe literal); `TEMPLATE_SYNTAX_ERROR` when it cannot. */
    public static Names syntax(String field, String text) {
        var variables = new LinkedHashSet<String>();
        Matcher matcher = VARIABLE.matcher(text);
        int count = 0;
        while (matcher.find()) { variables.add(matcher.group(1)); count++; }
        if (occurrences(text, "[[") != count || occurrences(text, "]]") != count) { throw syntaxError(field); }
        // Every apostrophe is literal (the renderer doubles them), so no brace is ever quoted: they must balance (ICU alone
        // reads a stray top-level `}` as text).
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '{') { depth++; }
            else if (text.charAt(i) == '}' && --depth < 0) { throw syntaxError(field); }
        }
        if (depth != 0) { throw syntaxError(field); }
        var icu = new LinkedHashSet<String>(); var selectors = new LinkedHashSet<String>(); var printed = new LinkedHashSet<String>();
        try {
            var pattern = new MessagePattern(MessagePattern.ApostropheMode.DOUBLE_OPTIONAL).parse(text.replace("''", "'").replace("'", "''"));
            for (int i = 0; i + 1 < pattern.countParts(); i++) {
                // Every argument is ARG_START followed by its ARG_NAME or ARG_NUMBER; ARG_START's type says how it is used.
                var part = pattern.getPart(i);
                if (part.getType() != MessagePattern.Part.Type.ARG_START) { continue; }
                var name = pattern.getPart(i + 1);
                String argument = name.getType() == MessagePattern.Part.Type.ARG_NUMBER ? Integer.toString(name.getValue()) : pattern.getSubstring(name);
                icu.add(argument);
                (SELECTING.contains(part.getArgType()) ? selectors : printed).add(argument);
            }
        } catch (IllegalArgumentException | IndexOutOfBoundsException unparsable) { throw syntaxError(field); }
        return new Names(variables, icu, selectors, printed);
    }
    private static final Set<MessagePattern.ArgType> SELECTING = Set.of(MessagePattern.ArgType.SELECT, MessagePattern.ArgType.PLURAL,
            MessagePattern.ArgType.SELECTORDINAL);

    private static void texts(String field, Map<String, String> values, List<String> locales, String defaultLocale, boolean required, int max,
            List<Map<String, Object>> errors) {
        values.forEach((locale, text) -> {
            if (!locales.contains(locale)) { errors.add(error(field + "." + locale, "INVALID_VALUE")); }
            else if (text != null && text.length() > max) { errors.add(error(field + "." + locale, "INVALID_VALUE")); }
        });
        if (required && blank(values.get(defaultLocale))) { errors.add(error(field + "." + defaultLocale, "REQUIRED")); }
    }
    /**
     * `422 CHANNEL_NOT_ALLOWED` with the first refused cell as `details {audience, channel}` (what D9 marks, E76) and every
     * refused cell in `details.cells`.
     */
    public static ApiException channelNotAllowed(List<Map<String, Object>> cells) {
        var first = cells.getFirst();
        return new ApiException(ErrorCode.CHANNEL_NOT_ALLOWED, Map.of("audience", first.get("audience"), "channel", first.get("channel"), "cells", List.copyOf(cells)));
    }
    private static Map<String, Object> error(String field, String code) { return Map.of("field", field, "code", code); }
    private static ApiException syntaxError(String field) { return new ApiException(ErrorCode.TEMPLATE_SYNTAX_ERROR, Map.of("field", field)); }
    private static int occurrences(String text, String token) {
        int count = 0;
        for (int at = text.indexOf(token); at >= 0; at = text.indexOf(token, at + token.length())) { count++; }
        return count;
    }
    private static boolean blank(String text) { return text == null || text.isBlank(); }
}
