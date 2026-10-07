package com.agilityhub.core.shared.domain;

/** The EPC basic Latin character set shared by parameter validation and pain.008 identifiers. */
public final class SepaCharacters {
    private SepaCharacters() { }
    public static boolean containsOnly(String value) {
        return value != null && value.matches("[a-zA-Z0-9/\\-?:().,'+ ]+");
    }
}
