package com.agilityhub.core.clubs.scheduling.domain;

import java.time.*;
import java.time.format.TextStyle;
import java.util.*;

/** Pure S06 policy shared with dashboard and S15. */
public final class RiskEvaluator {
    public record Input(ClassState state, boolean exempt, int booked, LocalDate date) { }
    public record Policy(int minDogs, LocalTime reviewTime, int lookaheadDays, boolean autoCancel, ZoneId zone) { }
    public record Result(boolean atRisk, Instant reviewAt, String text) { }
    private final DescriptionResolver.Messages messages;
    public RiskEvaluator(DescriptionResolver.Messages messages) { this.messages = messages; }
    public Result evaluate(Input input, Policy policy, Instant now, Locale locale) {
        var review = WeekCalendarRules.resolve(input.date(), policy.reviewTime(), policy.zone()).instant();
        boolean risk = input.state() == ClassState.ACTIVE && !input.exempt() && input.booked() < policy.minDogs()
                && !input.date().isAfter(now.atZone(policy.zone()).toLocalDate().plusDays(policy.lookaheadDays())) && now.isBefore(review);
        var variables = Map.<String, Object>of("count", input.booked(), "minDogs", policy.minDogs(), "time", policy.reviewTime().toString(),
                "day", input.date().getDayOfWeek().getDisplayName(TextStyle.FULL, locale));
        return new Result(risk, review, risk ? messages.format(policy.autoCancel() ? "scheduling.risk.tooltip" : "scheduling.risk.tooltipNoAutoCancel", variables, locale) : null);
    }
}
