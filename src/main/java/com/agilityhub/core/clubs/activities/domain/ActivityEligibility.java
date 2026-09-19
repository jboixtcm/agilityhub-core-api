package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;

public final class ActivityEligibility {
    public record Dog(String id, boolean active, String levelId) { }
    public record Inactivity(LocalDate from, LocalDate to) { }
    public record Member(String status, boolean membershipActive, LocalDate leaveDate, Map<String,Object> block, Map<String,Object> actorBlock,
                         List<Inactivity> inactivity, List<Dog> dogs) { }
    private ActivityEligibility() { }
    public static void check(ActivityState state, ActivityTimes times, LocalDate date, Member member, List<String> levels,
                             boolean levelsEnabled, boolean inactivityEnabled, boolean registered, String dogId, Instant now) {
        if (state == ActivityState.DRAFT) throw new ApiException(ErrorCode.NOT_FOUND);
        if (state != ActivityState.PUBLISHED) throw new ApiException(ErrorCode.ACTIVITY_NOT_PUBLISHED);
        if (!times.registrationOpen(now)) throw new ApiException(ErrorCode.REGISTRATION_CLOSED,
                Map.of("reason", now.isBefore(times.registrationOpensAt()) ? "NOT_YET_OPEN" : "CLOSED", "opensAt", times.registrationOpensAt(), "closesAt", times.registrationClosesAt()));
        if (!"ACTIVE".equals(member.status()) || !member.membershipActive()) throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE);
        if (member.leaveDate() != null && !date.isBefore(member.leaveDate())) throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE, Map.of("reason", "LEAVING"));
        for (var block : List.of(member.block(), member.actorBlock())) if (Boolean.TRUE.equals(block.get("active"))) {
            var details = new LinkedHashMap<String,Object>();
            for (String key : List.of("reason", "since")) if (block.get(key) != null) details.put(key, block.get(key));
            throw new ApiException(ErrorCode.BOOKING_BLOCKED, details);
        }
        if (inactivityEnabled) for (var period : member.inactivity()) if (!date.isBefore(period.from()) && !date.isAfter(period.to()))
            throw new ApiException(ErrorCode.INACTIVITY_PERIOD, Map.of("from", period.from(), "to", period.to()));
        if (!admitted(member.dogs(), levels, levelsEnabled, dogId)) throw new ApiException(ErrorCode.LEVEL_NOT_ALLOWED);
        if (registered) throw new ApiException(ErrorCode.ALREADY_REGISTERED);
    }
    public static boolean admitted(List<Dog> dogs, List<String> levels, boolean enabled, String dogId) {
        return !enabled || levels.isEmpty() || dogs.stream().anyMatch(d -> d.active() && (dogId == null || dogId.equals(d.id())) && levels.contains(d.levelId()));
    }
}
