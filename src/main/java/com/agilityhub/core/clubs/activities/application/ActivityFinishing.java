package com.agilityhub.core.clubs.activities.application;

import com.agilityhub.core.clubs.bookings.application.ports.ActivityFinishingPort;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** S07 → S15 P8 step (c) (E6-T04): {@link ActivityFinishingPort} over E4-T04's {@link ActivityLifecycleService} finishing. */
@Service
public class ActivityFinishing implements ActivityFinishingPort {
    private final ActivityLifecycleService lifecycle;
    public ActivityFinishing(ActivityLifecycleService lifecycle) { this.lifecycle = lifecycle; }
    @Override public List<String> endedBy(Instant now) { return lifecycle.endedBy(now); }
    @Override public boolean finish(String activityId, Instant now) { return lifecycle.finish(activityId, now); }
}
