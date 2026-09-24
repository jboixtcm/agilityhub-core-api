package com.agilityhub.core.clubs.common.api;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;

/**
 * S15 §6 form A (`GET /risk-review`, D1 card). Published as `RiskReviewForm` because the S14 dashboard
 * already publishes a different `RiskReview` schema inside `GET /dashboard` (E3-T04).
 */
public final class RiskReviewContracts {
    private RiskReviewContracts() { }
    public enum RiskDayLabel { TODAY, TOMORROW, OTHER }
    /** WILL_REVIEW replaces WILL_CANCEL when classes.riskAutoCancelSameDay = false. */
    public enum RiskReviewStatus { AUTO_CANCELLED, AT_RISK, WILL_CANCEL, WILL_REVIEW }
    @Schema(name = "RiskReviewForm", description = "S15 §6 form A: ACTIVE classes at risk and the ones already CANCELLED{RISK_REVIEW} of [date, date + lookaheadDays], by startsAt")
    public record RiskReview(LocalDate date, @Schema(example = "07:30") String reviewTime, int lookaheadDays, int minDogs, boolean autoCancelSameDay,
            List<RiskReviewItem> items) { }
    public record RiskReviewItem(String classId, LocalDate date, RiskDayLabel dayLabel, @Schema(example = "09:30") String startTime,
            String displayDescription, @Schema(requiredMode = NOT_REQUIRED, nullable = true) String ringName, int bookedCount, RiskReviewStatus status,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "AUTO_CANCELLED only") Instant cancelledAt,
            @Schema(requiredMode = NOT_REQUIRED, nullable = true, description = "classes.riskReviewTime of the class day") Instant reviewAt,
            @Schema(description = "From risk.notifiedBookingIds, or the affected bookings when cancelled") List<RiskReviewNotified> notified) { }
    public record RiskReviewNotified(String memberName, String dogName) { }

    static RiskReview from(com.agilityhub.core.clubs.scheduling.application.RiskReviewQuery.Review review) {
        return new RiskReview(review.date(), review.reviewTime(), review.lookaheadDays(), review.minDogs(), review.autoCancelSameDay(),
                review.items().stream().map(item -> new RiskReviewItem(item.classId(), item.date(), RiskDayLabel.valueOf(item.dayLabel()), item.startTime(),
                        item.displayDescription(), item.ringName(), item.bookedCount(), RiskReviewStatus.valueOf(item.status()), item.cancelledAt(), item.reviewAt(),
                        item.notified().stream().map(n -> new RiskReviewNotified(n.memberName(), n.dogName())).toList())).toList());
    }
}
