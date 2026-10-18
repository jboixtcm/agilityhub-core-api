package com.agilityhub.core.shared.domain;

/** S03 R-03-27: the one masking format, shared by the census reads and the export engine (S14 R-14-12). */
public final class Masking {
    private Masking() { }
    /** `idDocument` → 2 first + «······» + 2 last (`fmtMaskedId`): `38xxxxxx1P` → «38······1P»; 4 characters or fewer → «······». */
    public static String idDocument(String value) {
        if (value == null || value.length() <= 4) { return "······"; }
        return value.substring(0, 2) + "······" + value.substring(value.length() - 2);
    }
}
