package com.agilityhub.core.clubs.scheduling.application;
import java.time.Instant;
import java.util.List;
/** S07 supplies a validated activity window; scheduling owns conflict resolution. */
public record ActivityBlockRequest(String activityId,List<String> ringIds,Instant from,Instant to,String createdByAccountId) {
    public ActivityBlockRequest { ringIds=List.copyOf(ringIds); }
}
