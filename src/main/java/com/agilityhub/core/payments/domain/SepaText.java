package com.agilityhub.core.payments.domain;

import java.text.Normalizer;

/**
 * S12 R-12-12, §10 (E8-T03): a free text of a pain.008 file in the SEPA character set (the EPC's basic Latin set: letters,
 * digits, `/ - ? : ( ) . , ' +` and the space). The text is decomposed (NFKD) and its accents dropped (`ç→c`, `à→a`), `·` becomes
 * `-` (`Cànic · Núria` → `Canic - Nuria`, `col·legi` → `col-legi`), every other character becomes a space, runs of spaces
 * collapse to one and the ends are trimmed; then the text is cut at {@code max} characters. The result is ASCII, so the cut
 * always falls on a character boundary.
 */
public final class SepaText {
    private SepaText() { }
    /** `RmtInf.Ustrd` (`Max140Text`). */
    public static final int REMITTANCE_INFORMATION = 140;
    /** `Nm` of the creditor, the initiating party and the debtor (`Max70Text` in the EPC rules). */
    public static final int NAME = 70;

    /** {@code text} in the SEPA character set, at most {@code max} characters; empty when nothing of it is left. */
    public static String of(String text, int max) {
        String clean = transliterate(text);
        return clean.length() <= max ? clean : clean.substring(0, max).stripTrailing();
    }

    static String transliterate(String text) {
        if (text == null) { return ""; }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
        var out = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            out.append(c == '·' ? '-' : allowed(c) ? c : ' ');
        }
        return out.toString().replaceAll(" {2,}", " ").strip();
    }

    static boolean allowed(char c) {
        return c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || "/-?:().,'+ ".indexOf(c) >= 0;
    }
}
