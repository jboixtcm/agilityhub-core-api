package com.agilityhub.core.clubs.census.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;

public final class CensusRules {
    private CensusRules() { }
    public record DisplayStatus(String kind, LocalDate date) { }
    public static DisplayStatus status(String status, LocalDate leave, LocalDate inactivityEnd, Instant erased, LocalDate today) {
        if (erased != null) { return new DisplayStatus("ERASED", null); }
        if ("ACTIVE".equals(status)) {
            if (inactivityEnd != null && !inactivityEnd.isBefore(today)) { return new DisplayStatus("INACTIVE_PERIOD", inactivityEnd); }
            if (leave != null && !leave.isBefore(today)) { return new DisplayStatus("LEAVE_SCHEDULED", leave); }
        }
        return new DisplayStatus(status, "LEFT".equals(status) ? leave : null);
    }
    public static void transition(String before, String after, String reason) {
        boolean valid = switch (before) {
            case "PENDING" -> "ACTIVE".equals(after) || ("LEFT".equals(after) && reason != null && !reason.isBlank());
            case "ACTIVE" -> "LEFT".equals(after);
            // INACTIVE remains readable for migrated E1 identity records; new inactivity uses periods.
            case "INACTIVE", "LEFT" -> "ACTIVE".equals(after);
            default -> false;
        };
        if (!valid) { throw new ApiException(ErrorCode.INVALID_STATE); }
    }
    public static String maskedIban(String value) {
        if (value == null || value.isBlank()) { return null; }
        String compact = value.replaceAll("\\s", "");
        return "···· ···· ···· ···· " + compact.substring(Math.max(0, compact.length() - 4));
    }
    public static String maskedId(String value) {
        if (value == null || value.length() <= 4) { return "······"; }
        return value.substring(0, 2) + "······" + value.substring(value.length() - 2);
    }
    /** pain.008 `MndtId` is `Max35Text`. */
    public static final int MANDATE_REF_MAX = 35;
    /**
     * E43 (S12 §7, S18 R-18-08): the mandate of a member is `{clubSlug}-{memberNumber}-{n}`, the migrated format, never above
     * 35 characters: a long slug is cut. The last two segments are the member number and {@code n}, so two members of a club
     * never share a reference. {@code n} is 1, or the next one after {@code previous} when that was this member's own mandate
     * (a readmission signs a new mandate, and a creditor never reuses a reference).
     */
    public static String mandateRef(String clubSlug, int memberNumber, String previous) {
        return mandateRef(clubSlug, memberNumber, previous, 0);
    }
    public static String mandateRef(String clubSlug, int memberNumber, String previous, int lastSequence) {
        return mandatePrefix(clubSlug, memberNumber) + (Math.max(lastSequence, mandateSequence(clubSlug, memberNumber, previous)) + 1);
    }
    /** Legacy methods keep only the reference; E90 also retains a sequence when the payment type changes. */
    public static int lastMandateSequence(String clubSlug, int memberNumber, Map<String, Object> payment) {
        int saved = payment.get("lastMandateSequence") instanceof Number number ? number.intValue() : 0;
        return Math.max(saved, mandateSequence(clubSlug, memberNumber, (String) payment.get("mandateRef")));
    }
    /**
     * E92: every writer that replaces a member's payment method (card setup or completion, readmission) carries the last
     * mandate sequence over, so a later return to SEPA_DD never reuses a reference. The sequence is never in a response.
     */
    public static Map<String, Object> keepMandateSequence(String clubSlug, Integer memberNumber, Map<String, Object> previous,
                                                          Map<String, Object> replacement) {
        if (replacement == null) { return null; }
        int sequence = Math.max(sequence(clubSlug, memberNumber, previous), sequence(clubSlug, memberNumber, replacement));
        var kept = new LinkedHashMap<>(replacement);
        if (sequence > 0) { kept.put("lastMandateSequence", sequence); }
        return kept;
    }
    private static int sequence(String clubSlug, Integer memberNumber, Map<String, Object> payment) {
        if (payment == null) { return 0; }
        if (memberNumber == null) { return payment.get("lastMandateSequence") instanceof Number number ? number.intValue() : 0; }
        return lastMandateSequence(clubSlug, memberNumber, payment);
    }
    private static int mandateSequence(String clubSlug, int memberNumber, String previous) {
        String prefix = mandatePrefix(clubSlug, memberNumber);
        if (previous == null || !previous.startsWith(prefix)) { return 0; }
        String sequence = previous.substring(prefix.length());
        return sequence.matches("[0-9]{1,4}") ? Integer.parseInt(sequence) : 0;
    }
    private static String mandatePrefix(String clubSlug, int memberNumber) {
        String suffix = "-" + memberNumber + "-";
        String slug = clubSlug.substring(0, Math.min(clubSlug.length(), MANDATE_REF_MAX - suffix.length() - 4)).replaceAll("-+$", "");
        return slug + suffix;
    }
    public static int age(LocalDate birth, LocalDate today) { return birth == null ? 0 : Period.between(birth, today).getYears(); }
    public static void mutable(Instant erasedAt) { if (erasedAt != null) { throw new ApiException(ErrorCode.MEMBER_ERASED); } }
}
