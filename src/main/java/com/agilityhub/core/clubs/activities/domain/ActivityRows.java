package com.agilityhub.core.clubs.activities.domain;

import java.util.*;

public final class ActivityRows {
    private ActivityRows() { }
    public static String historyState(RegistrationState state, RegistrationCancelReason reason, ActivityState activity) {
        if (state == RegistrationState.WAITLISTED) return null;
        if (state == RegistrationState.ACTIVE) return activity == ActivityState.FINISHED ? "DONE" : null;
        return reason == RegistrationCancelReason.ACTIVITY_CANCELLED ? "CANCELLED_BY_CLUB" : "CANCELLED";
    }
    public static String place(boolean atClub, String location, List<String> ids, Map<String,String> activeRings, Map<String,String> allRings, String allLabel) {
        if (!atClub) return location;
        if (!ids.isEmpty() && new HashSet<>(ids).equals(activeRings.keySet())) return allLabel;
        return String.join(", ", ids.stream().map(id -> allRings.getOrDefault(id, "—")).toList());
    }
}
