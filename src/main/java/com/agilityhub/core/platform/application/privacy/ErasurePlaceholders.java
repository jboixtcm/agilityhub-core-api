package com.agilityhub.core.platform.application.privacy;

/**
 * S14 §10 `erasure.placeholder.{member, account, dog, task}`: «Abonat suprimit», «Compte suprimit», «Gos suprimit», «(suprimit)»,
 * stored in the club's default locale.
 */
public record ErasurePlaceholders(String member, String account, String dog, String task) {
    /** S14 R-14-15 `lastName1 → «#{memberNumber}»`; a member without a number → «#—». */
    public static String number(Integer memberNumber) { return "#" + (memberNumber == null ? "—" : memberNumber); }
}
