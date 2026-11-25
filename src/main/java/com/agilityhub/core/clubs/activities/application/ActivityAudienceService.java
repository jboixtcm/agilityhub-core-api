package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.activities.domain.ActivityEligibility;
import com.agilityhub.core.platform.application.Module;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ActivityAudienceService {
    private final ActivityContext context;
    public ActivityAudienceService(ActivityContext context) { this.context=context; }
    public List<String> admittedMemberIds(List<String> levelIds) {
        if(!context.enabled(Module.ACTIVITIES)) return List.of();
        return context.members.activeIds().stream().filter(id -> {
            var member = context.eligibility(id);
            return member.membershipActive() && ActivityEligibility.admitted(member.dogs(), levelIds, true, null);
        }).toList();
    }
}
