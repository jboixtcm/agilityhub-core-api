package com.agilityhub.core.clubs.scheduling.application.ports;

import java.time.Instant;
import java.util.*;

public interface TrainingOccupancyPort {
    enum Type { TRAINING, RING_BLOCK }
    record Interval(String ringId, Instant from, Instant to, Type type, String reason,
            String memberName, String dogName, String note, String id) { }
    List<Interval> occupancy(Instant from, Instant to, Collection<String> ringIds, String viewerRole);
}
