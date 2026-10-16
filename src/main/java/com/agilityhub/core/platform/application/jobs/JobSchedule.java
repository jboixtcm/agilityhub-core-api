package com.agilityhub.core.platform.application.jobs;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;
import java.util.function.Function;

/** A catalog row resolved against one club's parameters (S15 §6 `schedule{kind, localTime?, dayOfWeek?, dayOfMonth?}`). */
public record JobSchedule(Cadence kind, LocalTime localTime, DayOfWeek dayOfWeek, Integer dayOfMonth) {
    static final int FIRST_DAY = 1;
    /** `parameter` returns the club value of a catalog key; values are never constants in code (R-15-03). */
    public static JobSchedule resolve(JobDefinition definition, Function<String, Object> parameter) {
        return switch (definition.cadence()) {
            case CONTINUOUS -> new JobSchedule(Cadence.CONTINUOUS, null, null, null);
            case DAILY -> new JobSchedule(Cadence.DAILY, time(parameter.apply(definition.localTimeParameter())), null, null);
            case WEEKLY -> {
                var value = (Map<?, ?>) parameter.apply(definition.localTimeParameter());
                yield new JobSchedule(Cadence.WEEKLY, time(value.get("time")), DayOfWeek.valueOf(value.get("dayOfWeek").toString()), null);
            }
            // A monthly row without a day parameter runs on the first of the month (E11-T01: S14's retention sweep).
            case MONTHLY -> new JobSchedule(Cadence.MONTHLY, time(parameter.apply(definition.localTimeParameter())), null,
                    definition.dayParameter() == null ? FIRST_DAY : ((Number) parameter.apply(definition.dayParameter())).intValue());
        };
    }
    private static LocalTime time(Object value) { return LocalTime.parse(value.toString()); }
}
