package com.agilityhub.core.clubs.dashboard.application.ports;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.Notified;
import com.agilityhub.core.shared.domain.LocalizedText;
import java.time.*;
import java.util.List;

/**
 * S14 R-14-06 D1 card source: the S15 §6 form-A rows of `GET /risk-review` for `[today, today +
 * classes.riskLookaheadDays]`, in `startsAt` order. S06/S15 own the risk policy and the statuses; the dashboard only
 * maps and localizes them (no second computation).
 */
public interface RiskReviewSource {
    List<Row> rows(String clubId, LocalDate today);
    /** `status` is the form-A value: `AUTO_CANCELLED`, `AT_RISK`, `WILL_CANCEL` or `WILL_REVIEW`. */
    record Row(String id, LocalDate date, LocalTime startTime, LocalizedText description, LocalizedText ringName,
            int bookedCount, String status, List<Notified> notified, Instant reviewAt) {
        public Row { notified = List.copyOf(notified); }
    }
}
