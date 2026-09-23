package com.agilityhub.core.platform.application.jobs;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.Map;
import java.util.function.Function;

/** A catalog row resolved against one club's parameters (S15 §6 `schedule{kind, localTime?, dayOfWeek?, dayOfMonth?}`). */
public record JobSchedule(Cadence kind, LocalTime localTime, DayOfWeek dayOfWeek, Integer dayOfMonth) {
    /** `parameter` returns the club value of a catalog key; values are never constants in code (R-15-03). */
    public static JobSchedule resolve(JobDefinition definition, Function<String, Object> parameter) {
        return switch (definition.cadence()) {
            case CONTINUOUS -> new JobSchedule(Cadence.CONTINUOUS, null, null, null);
            case DAILY -> new JobSchedule(Cadence.DAILY, time(parameter.apply(definition.localTimeParameter())), null, null);
            case WEEKLY -> {
                var value = (Map<?, ?>) parameter.apply(definition.localTimeParameter());
                yield new JobSchedule(Cadence.WEEKLY, time(value.get("time")), DayOfWeek.valueOf(value.get("dayOfWeek").toString()), null);
            }
            case MONTHLY -> new JobSchedule(Cadence.MONTHLY, time(parameter.apply(definition.localTimeParameter())), null,
                    ((Number) parameter.apply(definition.dayParameter())).intValue());
        };
    }
    private static LocalTime time(Object value) { return LocalTime.parse(value.toString()); }
}
