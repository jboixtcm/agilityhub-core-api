package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.stereotype.Component;

@Component
public class FinishActivitiesCommand implements CoreCommand {
    private final ClubConfigService clubs; private final ActivityLifecycleService sessions; private final Clock clock;
    public FinishActivitiesCommand(ClubConfigService clubs,ActivityLifecycleService sessions,Clock clock) { this.clubs=clubs; this.sessions=sessions; this.clock=clock; }
    public String name() { return "activities:finish-ended"; }
    public void run(ApplicationArguments args) {
        var values=args.getOptionValues("club");
        if(!args.getNonOptionArgs().isEmpty() || !Set.of("core.command","club").containsAll(args.getOptionNames()) || values!=null && values.size()!=1)
            throw new IllegalArgumentException("Usage: activities:finish-ended [--club=<slug>]");
        var ids=values==null?clubs.activeClubIds():List.of(clubs.findClubIdBySlug(values.getFirst()).orElseThrow(() -> new ApiException(ErrorCode.CLUB_NOT_FOUND)));
        for(String id:ids) try(var tenant=TenantContext.open(id)) { System.out.println("Finished activities: "+sessions.finishEnded(clock.instant())); }
    }
}
