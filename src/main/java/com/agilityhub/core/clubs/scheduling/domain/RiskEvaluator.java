package com.agilityhub.core.clubs.scheduling.domain;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.*;

/** Pure S06 policy shared with dashboard and S15. */
public final class RiskEvaluator {
    public record Input(ClassState state, boolean exempt, int booked, LocalDate date, Instant startsAt) { }
    /** @param reviewOn whether the S15 `risk-review` job (P2) is switched on (`jobs.riskReview.enabled`) */
    public record Policy(int minDogs, LocalTime reviewTime, int lookaheadDays, boolean autoCancel, boolean reviewOn, ZoneId zone) { }
    public record Result(boolean atRisk, Instant reviewAt, String text) { }
    /** «7:30», «18:50»: the review time as R-06-13's example and the notices write it (S11 R-11-05), never «07:30». */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("H:mm", Locale.ROOT);
    private final DescriptionResolver.Messages messages;
    public RiskEvaluator(DescriptionResolver.Messages messages) { this.messages = messages; }
    /**
     * S15 §6 (E37): P2 will really review a class only while the `risk-review` job is on, the class's `reviewAt` is still
     * ahead and the class starts after it (at `reviewAt` P2 skips a class that has begun: RiskReviewJob's skippedStarted).
     */
    public static boolean reviewWillRun(boolean reviewOn, Instant reviewAt, Instant startsAt, Instant now) {
        return reviewOn && reviewAt.isAfter(now) && startsAt.isAfter(reviewAt);
    }
    public Result evaluate(Input input, Policy policy, Instant now, Locale locale) {
        var review = WeekCalendarRules.resolve(input.date(), policy.reviewTime(), policy.zone()).instant();
        boolean risk = input.state() == ClassState.ACTIVE && !input.exempt() && input.booked() < policy.minDogs()
                && !input.date().isAfter(now.atZone(policy.zone()).toLocalDate().plusDays(policy.lookaheadDays())) && now.isBefore(review);
        var variables = Map.<String, Object>of("count", input.booked(), "minDogs", policy.minDogs(), "time", policy.reviewTime().format(CLOCK),
                "day", input.date().getDayOfWeek().getDisplayName(TextStyle.FULL, locale));
        // INC-40 (E11-T10): «s'anul·larà» is a promise about P2 (as WILL_CANCEL in GET /risk-review); otherwise the club decides.
        boolean cancels = policy.autoCancel() && reviewWillRun(policy.reviewOn(), review, input.startsAt(), now);
        return new Result(risk, review, risk ? messages.format(cancels ? "scheduling.risk.tooltip" : "scheduling.risk.tooltipNoAutoCancel", variables, locale) : null);
    }
}
