package com.agilityhub.core.clubs.census.application;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/**
 * S15 R-15-14 P4 reads the reminder lead each member chose (S11 R-11-04, `Member.notificationPreferences.reminderMinutesBefore`;
 * absent or `null` = «Mai») in the current club. Erased members have none.
 */
@Service
public class ReminderLeads {
    static final String KEY = "reminderMinutesBefore";
    private final CensusAccess access;
    public ReminderLeads(CensusAccess access) { this.access = access; }

    /** The lead in minutes of each member that has one; members without a lead are absent. */
    public Map<String, Integer> of(Collection<String> memberIds) {
        var leads = new HashMap<String, Integer>();
        if (memberIds.isEmpty()) { return leads; }
        access.members.matching(Criteria.where("_id").in(memberIds).and("erasedAt").is(null)).forEach(member -> {
            if (member.notificationPreferences != null && member.notificationPreferences.get(KEY) instanceof Number minutes) { leads.put(member.id, minutes.intValue()); }
        });
        return leads;
    }
}
