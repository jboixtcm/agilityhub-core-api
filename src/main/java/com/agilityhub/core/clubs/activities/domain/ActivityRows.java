package com.agilityhub.core.clubs.activities.domain;

import java.util.*;

public final class ActivityRows {
    private ActivityRows() { }
    public static String historyState(RegistrationState state, RegistrationCancelReason reason, ActivityState activity) {
        if (state == RegistrationState.WAITLISTED) return null;
        if (state == RegistrationState.ACTIVE) return activity == ActivityState.FINISHED ? "DONE" : null;
        return reason == RegistrationCancelReason.ACTIVITY_CANCELLED ? "CANCELLED_BY_CLUB" : "CANCELLED";
    }
    /**
     * R-07-08 (E5-T20): the 1-based rank of each waiting registration among the activity's active waiting entries, in
     * `position` order (the promotion order, ties by id). `positions` holds only WAITLISTED registrations; `position` itself is
     * the stored order and keeps the gaps left by cancellations and promotions.
     */
    public static Map<String, Integer> waitlistRanks(Map<String, Integer> positions) {
        var ids = new ArrayList<>(positions.keySet());
        ids.sort(Comparator.comparing((String id) -> positions.get(id), Comparator.nullsLast(Comparator.<Integer>naturalOrder()))
                .thenComparing(Comparator.naturalOrder()));
        var ranks = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < ids.size(); i++) ranks.put(ids.get(i), i + 1);
        return ranks;
    }
    public static String place(boolean atClub, String location, List<String> ids, Map<String,String> activeRings, Map<String,String> allRings, String allLabel) {
        if (!atClub) return location;
        if (!ids.isEmpty() && new HashSet<>(ids).equals(activeRings.keySet())) return allLabel;
        return String.join(", ", ids.stream().map(id -> allRings.getOrDefault(id, "—")).toList());
    }
}
