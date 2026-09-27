package com.agilityhub.core.clubs.messaging.application.engine;

import com.agilityhub.core.clubs.messaging.domain.SmsText;
import com.ibm.icu.text.MessageFormat;
import com.ibm.icu.text.MessagePattern;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * S11 R-11-05, one text for one recipient: (2) ICU4J `MessageFormat` with the variables as named arguments (`gender`,
 * `late`, `decision`, `kind`, `count` … and any `{var}` of the product copy) over a pattern whose apostrophes are all doubled
 * first — so every apostrophe of a template is literal («l'app», «d'aquest», «d'{dog_name}», inside a `select` branch too)
 * and a template never needs ICU quoting (the aim of S11's `DOUBLE_REQUIRED`; see {@link #text}); (3) `[[var]]` substitution with the already formatted
 * values, in one pass (a value is never re-read as a template); a variable the code does not have renders empty with a
 * `WARN` (the `400` belongs to saving, E7-T03); (4) the first letter of the title and the body in upper case. A pattern
 * ICU cannot parse (a template saved before validation) renders its raw text with a `WARN`, never an error.
 */
public final class TemplateRenderer {
    private static final Logger LOG = LoggerFactory.getLogger(TemplateRenderer.class);
    private static final Pattern VARIABLE = Pattern.compile("\\[\\[\\s*([A-Za-z0-9_]+)\\s*]]");

    /** The rendered texts of one notification; `sms` is null without an SMS body. */
    public record Rendered(String title, String body, SmsText.Sms sms) { }

    /**
     * @param known the variables the code may use (catalog row, derived forms, custom ones); others render empty with a WARN
     */
    public Rendered render(String code, String title, String body, String sms, Map<String, ?> variables, Locale locale, Collection<String> known,
            boolean transliterate) {
        String renderedSms = sms == null || sms.isBlank() ? null : text(code, sms, variables, locale, known);
        return new Rendered(capitalize(text(code, title, variables, locale, known)), capitalize(text(code, body, variables, locale, known)),
                renderedSms == null ? null : SmsText.of(renderedSms, transliterate));
    }

    /** Steps (2) and (3) of R-11-05 over one pattern. */
    public String text(String code, String pattern, Map<String, ?> variables, Locale locale, Collection<String> known) {
        if (pattern == null) { return ""; }
        String icu;
        try {
            var format = new MessageFormat("", locale);
            // Every apostrophe doubled (one already escaped for ICU, `''`, counts once) is a literal apostrophe in both modes.
            // DOUBLE_OPTIONAL, not the DOUBLE_REQUIRED S11 names: that is ICU4J's JDK mode, which re-parses a `select`
            // sub-message and so drops its apostrophes and leaves its `{var}` unformatted (TemplateRendererTest, T-11-04).
            format.applyPattern(pattern.replace("''", "'").replace("'", "''"), MessagePattern.ApostropheMode.DOUBLE_OPTIONAL);
            icu = format.format(new java.util.HashMap<String, Object>(variables));
        } catch (IllegalArgumentException unparsable) {
            LOG.warn("Notification template of {} is not valid ICU; rendered as plain text", code);
            icu = pattern;
        }
        Matcher matcher = VARIABLE.matcher(icu);
        var out = new StringBuilder();
        Set<String> allowed = known == null ? Set.of() : Set.copyOf(known);
        while (matcher.find()) {
            String key = matcher.group(1);
            Object value = variables.get(key);
            if (!allowed.contains(key)) { LOG.warn("Notification template of {} uses the unknown variable {}", code, key); value = null; }
            matcher.appendReplacement(out, Matcher.quoteReplacement(Objects.toString(value, "")));
        }
        matcher.appendTail(out);
        return out.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    /** Step (4): the first letter in upper case («Demà 9:30…», «Ahir no vas…»); leading marks and quotes stay. */
    public static String capitalize(String text) {
        if (text == null || text.isEmpty()) { return text == null ? "" : text; }
        int[] codePoints = text.codePoints().toArray();
        for (int i = 0; i < codePoints.length; i++) {
            if (Character.isLetter(codePoints[i])) {
                if (Character.isUpperCase(codePoints[i])) { return text; }
                codePoints[i] = Character.toUpperCase(codePoints[i]);
                return new String(codePoints, 0, codePoints.length);
            }
            if (Character.isDigit(codePoints[i])) { return text; }
        }
        return text;
    }
}
