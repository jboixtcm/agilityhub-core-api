package com.agilityhub.core.shared.domain;

import java.util.Locale;

/**
 * The SEPA creditor scheme identifier (AT-02) — S12 R-12-12 `CdtrSchmeId`, S17 R-17-05 (E11-T15 step 8, the audit of 21-10): the
 * country code, two check digits, the three-character creditor business code and the national identifier (`ES26000G12345678`).
 * The check digits are ISO 7064 mod 97-10 over the identifier without its business code: the national identifier, then the
 * country code and the check digits, letters as numbers (A = 10 … Z = 35), must leave 1, with the canonical digits 02–98 only
 * (round 2: «00» and «01» are refused although they leave 1 too). The business code is not covered, so the
 * club's `suffix` (spliced in at run time) never changes the check. Spaces and case are ignored, as the console stores them.
 * Shared by the console's validation (payments) and the stored creditor's re-check (platform), which cannot see each other.
 */
public final class SepaCreditorIdentifier {
    private SepaCreditorIdentifier() { }

    public static boolean valid(String identifier) {
        if (identifier == null) { return false; }
        String id = identifier.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!id.matches("[A-Z]{2}[0-9]{2}[A-Z0-9]{3}[A-Z0-9]{1,28}")) { return false; }
        // E11-T15 round 2: ISO 7064 gives 02–98 (98 − n mod 97); «00», «01» and «99» leave the right remainder but no bank takes them.
        int digits = Integer.parseInt(id.substring(2, 4));
        if (digits < 2 || digits > 98) { return false; }
        int remainder = 0;
        for (char c : (id.substring(7) + id.substring(0, 4)).toCharArray()) {
            int n = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = (remainder * (n < 10 ? 10 : 100) + n) % 97;
        }
        return remainder == 1;
    }
}
