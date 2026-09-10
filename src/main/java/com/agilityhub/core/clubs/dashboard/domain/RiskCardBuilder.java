package com.agilityhub.core.clubs.dashboard.domain;

import com.agilityhub.core.clubs.dashboard.application.DashboardData.*;
import com.agilityhub.core.clubs.dashboard.application.ports.*;
import java.time.*;
import java.util.*;

public final class RiskCardBuilder {
    private final RiskEvaluator evaluator;
    public RiskCardBuilder(RiskEvaluator evaluator) { this.evaluator = evaluator; }
    public RiskReview build(List<ClassSessionsQuery.Session> sessions, DashboardPeriod period, int lookahead,
            LocalTime reviewTime, boolean autoCancel, String locale, String defaultLocale) {
        var items = new ArrayList<RiskItem>();
        for (var session : sessions) {
            if (session.riskExempt() || session.date().isBefore(period.today()) || session.date().isAfter(period.today().plusDays(lookahead))) { continue; }
            RiskStatus status; List<Notified> notified;
            if ("CANCELLED".equals(session.state())) {
                if (!"RISK_REVIEW".equals(session.cancellationReason())) { continue; }
                status = RiskStatus.CANCELLED; notified = session.cancellationNotified();
            } else {
                if (!"ACTIVE".equals(session.state()) || !evaluator.atRisk(session)) { continue; }
                notified = session.riskNotice() ? session.riskNotified() : List.of();
                status = session.riskNotice() ? RiskStatus.AT_RISK : !autoCancel ? RiskStatus.PENDING_DECISION
                        : session.booked() == 0 ? RiskStatus.WILL_CANCEL : RiskStatus.AT_RISK;
            }
            items.add(new RiskItem(session.id(), session.date(), session.startTime().toString(),
                    session.description().withDefaultLocale(defaultLocale).resolve(locale).value(),
                    session.ringName().withDefaultLocale(defaultLocale).resolve(locale).value(), session.booked(), status, notified,
                    session.date().atTime(reviewTime).atZone(period.zone()).toInstant()));
        }
        items.sort(Comparator.comparing(RiskItem::date).thenComparing(RiskItem::startTime).thenComparing(RiskItem::classSessionId));
        return new RiskReview(reviewTime.toString(), lookahead, autoCancel, items.size(), List.copyOf(items));
    }
}
