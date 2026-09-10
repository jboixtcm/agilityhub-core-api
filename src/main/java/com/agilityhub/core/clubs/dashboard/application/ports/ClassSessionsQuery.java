package com.agilityhub.core.clubs.dashboard.application.ports;

import java.time.*;
import java.util.List;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.clubs.dashboard.application.DashboardData.Notified;

public interface ClassSessionsQuery {
    /** Bulk projection with already joined notification names; no per-row repository calls. */
    List<Session> sessions(String clubId, LocalDate from, LocalDate through);
    record Session(String id, LocalDate date, LocalTime startTime, LocalizedText description,
            LocalizedText ringName, String state, String cancellationReason, boolean riskExempt,
            int booked, boolean riskNotice, List<Notified> cancellationNotified, List<Notified> riskNotified) {
        public Session { cancellationNotified = List.copyOf(cancellationNotified); riskNotified = List.copyOf(riskNotified); }
    }
}
