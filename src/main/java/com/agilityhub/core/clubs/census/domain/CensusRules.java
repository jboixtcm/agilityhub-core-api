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
    public static int age(LocalDate birth, LocalDate today) { return birth == null ? 0 : Period.between(birth, today).getYears(); }
    public static void mutable(Instant erasedAt) { if (erasedAt != null) { throw new ApiException(ErrorCode.MEMBER_ERASED); } }
}
