package com.agilityhub.core.clubs.messaging.domain;

import java.text.Normalizer;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * S11 R-11-06, the SMS text: never a link; transliterated to the GSM-7 basic alphabet when
 * `messaging.sms.transliterateToGsm7` (`í ó ú ç ï` → `i o u c i`, `·` → `.`, dashes → `-`, typographic double quotes and
 * guillemets → `"`, typographic single quotes → `'`, the ellipsis → `...`; any other letter outside GSM-7 loses its accent,
 * and a character without a GSM-7 form is dropped); at most {@value #MAX} characters, cut with an ellipsis (`...` in
 * GSM-7, `…` otherwise) — the full text goes by app and e-mail.
 */
public final class SmsText {
    public static final int MAX = 160;
    /** GSM 03.38 basic character set (the extension table is not used by the product copy). */
    private static final String GSM7 = "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";
    private static final Map<Integer, String> MAPPED = Map.ofEntries(Map.entry((int) '·', "."), Map.entry((int) '—', "-"), Map.entry((int) '–', "-"),
            Map.entry((int) '’', "'"), Map.entry((int) '‘', "'"), Map.entry((int) '“', "\""), Map.entry((int) '”', "\""), Map.entry((int) '«', "\""),
            Map.entry((int) '»', "\""), Map.entry((int) '…', "..."), Map.entry((int) 'ç', "c"), Map.entry(0x00A0, " "), Map.entry((int) '€', "EUR"),
            Map.entry((int) '→', "->"));
    private static final Pattern LINK = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");

    private SmsText() { }

    public record Sms(String text, boolean truncated) {
        /** SMS segments of the text: 160/153 characters in GSM-7, 70/67 otherwise (the D9 preview, E7-T03). */
        public int segments() {
            boolean gsm = text.codePoints().allMatch(c -> GSM7.indexOf(c) >= 0);
            int single = gsm ? 160 : 70, multi = gsm ? 153 : 67;
            return text.length() <= single ? 1 : (text.length() + multi - 1) / multi;
        }
    }

    /** The text to send: links removed, transliterated when asked, whitespace collapsed, cut at {@value #MAX}. */
    public static Sms of(String rendered, boolean transliterate) {
        String text = LINK.matcher(rendered == null ? "" : rendered).replaceAll("");
        if (transliterate) { text = gsm7(text); }
        text = text.replaceAll("[ \\t]+", " ").replaceAll(" ?\\n ?", "\n").strip();
        if (text.length() <= MAX) { return new Sms(text, false); }
        String ellipsis = transliterate ? "..." : "…";
        return new Sms(text.substring(0, MAX - ellipsis.length()).stripTrailing() + ellipsis, true);
    }

    /** Every character in the GSM-7 basic set, mapped, unaccented or dropped. */
    public static String gsm7(String text) {
        var out = new StringBuilder(text.length());
        text.codePoints().forEach(c -> {
            if (GSM7.indexOf(c) >= 0) { out.appendCodePoint(c); return; }
            String mapped = MAPPED.get(c);
            if (mapped != null) { out.append(mapped); return; }
            String base = Normalizer.normalize(new String(Character.toChars(c)), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
            if (!base.isEmpty() && base.codePoints().allMatch(b -> GSM7.indexOf(b) >= 0)) { out.append(base); }
            else if (Character.isWhitespace(c)) { out.append(' '); }
        });
        return out.toString();
    }
}
