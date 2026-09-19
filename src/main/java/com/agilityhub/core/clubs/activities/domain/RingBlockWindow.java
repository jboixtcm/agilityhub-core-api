package com.agilityhub.core.clubs.activities.domain;

import com.agilityhub.core.shared.domain.*;
import java.time.*;

public record RingBlockWindow(Instant from, Instant to) {
    public static RingBlockWindow of(LocalDate date, String start, String end, String from, String to, ZoneId zone,
                                     LocalTime open, LocalTime close, int minimumMinutes) {
        LocalTime startTime = ActivityRules.time(start), endTime = ActivityRules.time(end);
        LocalTime left = from == null ? startTime : ActivityRules.time(from), right = to == null ? endTime : ActivityRules.time(to);
        if (date == null || startTime == null || endTime == null || left == null || right == null
                || !left.isBefore(right) || left.isAfter(startTime) || right.isBefore(endTime)) throw new ApiException(ErrorCode.INVALID_TIME_RANGE);
        Instant first = date.atTime(left).atZone(zone).toInstant(), last = date.atTime(right).atZone(zone).toInstant();
        if (Duration.between(first,last).toMinutes() < minimumMinutes) throw new ApiException(ErrorCode.INVALID_TIME_RANGE);
        if (open == null || close == null || left.isBefore(open) || right.isAfter(close)) throw new ApiException(ErrorCode.OUTSIDE_OPENING_HOURS);
        return new RingBlockWindow(first,last);
    }
}
