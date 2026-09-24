package com.agilityhub.core.clubs.dashboard.domain;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.clubs.dashboard.application.ports.RiskReviewSource;
import java.time.*;
import java.util.*;

/**
 * S14 R-14-06: the D1 card is `GET /risk-review` (S15 §6 form A) with S14's status names; it never re-evaluates risk.
 */
public final class RiskCardBuilder {
    private RiskCardBuilder() { }
    public static RiskReview build(List<RiskReviewSource.Row> rows, int lookahead, LocalTime reviewTime, boolean autoCancel,
            String locale, String defaultLocale) {
        var items = rows.stream().map(row -> new RiskItem(row.id(), row.date(), row.startTime().toString(),
                row.description().withDefaultLocale(defaultLocale).resolve(locale).value(),
                row.ringName().withDefaultLocale(defaultLocale).resolve(locale).value(), row.bookedCount(), status(row.status()),
                row.notified(), row.reviewAt())).toList();
        return new RiskReview(reviewTime.toString(), lookahead, autoCancel, items.size(), items);
    }
    /** Form A → S14: `AUTO_CANCELLED` → `CANCELLED`, `WILL_REVIEW` → `PENDING_DECISION`, the rest keep their name. */
    public static RiskStatus status(String formA) {
        return switch (formA) {
            case "AUTO_CANCELLED" -> RiskStatus.CANCELLED;
            case "AT_RISK" -> RiskStatus.AT_RISK;
            case "WILL_CANCEL" -> RiskStatus.WILL_CANCEL;
            case "WILL_REVIEW" -> RiskStatus.PENDING_DECISION;
            default -> throw new IllegalArgumentException("Unknown risk-review status " + formA);
        };
    }
}
