package com.agilityhub.core.clubs.scheduling.application;

import com.agilityhub.core.clubs.census.application.SchedulingRecipients;
import com.agilityhub.core.clubs.scheduling.application.ports.ClassBookingsPort;
import com.agilityhub.core.clubs.scheduling.domain.ClassCancellationReason;
import com.agilityhub.core.clubs.scheduling.domain.ClassState;
import com.agilityhub.core.clubs.scheduling.domain.SchedulingCatalog;
import com.agilityhub.core.clubs.scheduling.domain.WeekCalendarRules;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSession;
import com.agilityhub.core.clubs.scheduling.persistence.ClassSessionRepository;
import com.agilityhub.core.shared.application.LocaleContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S15 §6 form A `GET /risk-review` (the D1 card, also embedded by S14 in `GET /dashboard` through the S06 dashboard
 * adapters): the ACTIVE classes at risk and the ones already CANCELLED{RISK_REVIEW} of `[date, date + lookaheadDays]`,
 * by `startsAt`. `notified` comes from `risk.notifiedBookingIds`, or from the affected bookings once cancelled.
 */
@Service
public class RiskReviewQuery {
    public record Notified(String memberName, String gender, String dogName) { }
    public record Item(String classId, LocalDate date, String dayLabel, String startTime, String displayDescription, String ringName, int bookedCount,
            String status, Instant cancelledAt, Instant reviewAt, List<Notified> notified) { }
    public record Review(LocalDate date, String reviewTime, int lookaheadDays, int minDogs, boolean autoCancelSameDay, List<Item> items) { }

    private final ClassSessionRepository classes; private final ClassBookingsPort bookings; private final SchedulingRecipients census;
    private final PlanningContext context; private final SessionProjection projection; private final Clock clock;
    public RiskReviewQuery(ClassSessionRepository classes, ClassBookingsPort bookings, SchedulingRecipients census, PlanningContext context,
            SessionProjection projection, Clock clock) {
        this.classes = classes; this.bookings = bookings; this.census = census; this.context = context; this.projection = projection; this.clock = clock;
    }

    public Review review(LocalDate requested) {
        var config = context.config(); var zone = projection.zone();
        var date = requested == null ? clock.instant().atZone(zone).toLocalDate() : requested;
        int lookahead = config.get("classes.riskLookaheadDays", Integer.class); int minDogs = config.get("classes.minDogs", Integer.class);
        boolean autoCancel = config.get("classes.riskAutoCancelSameDay", Boolean.class);
        var reviewTime = LocalTime.parse(config.get("classes.riskReviewTime", String.class));
        var catalog = context.catalog(); var locale = LocaleContext.current();
        var items = new ArrayList<Item>();
        for (ClassSession c : classes.startingBetween(date.atStartOfDay(zone).toInstant(), date.plusDays(lookahead + 1L).atStartOfDay(zone).toInstant())) {
            String label = c.date().equals(date) ? "TODAY" : c.date().equals(date.plusDays(1)) ? "TOMORROW" : "OTHER";
            String ring = catalog.rings().stream().filter(r -> r.id().equals(c.ringId())).map(SchedulingCatalog.Resource::name).findFirst().orElse(null);
            if (c.state() == ClassState.CANCELLED && c.cancellation() != null && c.cancellation().reason() == ClassCancellationReason.RISK_REVIEW) {
                items.add(new Item(c.id(), c.date(), label, c.startTime(), projection.description(c, locale), ring, c.cancellation().affectedBookings(),
                        "AUTO_CANCELLED", c.cancellation().at(), null, cancellationNotified(c)));
                continue;
            }
            if (c.state() != ClassState.ACTIVE || c.risk() != null && c.risk().exempt()) { continue; }
            int booked = bookings.activeBookings(c.id()).size();
            if (booked >= minDogs) { continue; }
            var notified = riskNotified(c);
            boolean warned = !notified.isEmpty() || c.risk() != null && c.risk().adminNotifiedAt() != null;
            // AT_RISK = warned registrants; otherwise the review will act (WILL_CANCEL), or the club decides (WILL_REVIEW).
            String status = warned && booked > 0 ? "AT_RISK" : autoCancel ? "WILL_CANCEL" : "WILL_REVIEW";
            items.add(new Item(c.id(), c.date(), label, c.startTime(), projection.description(c, locale), ring, booked, status, null,
                    WeekCalendarRules.resolve(c.date(), reviewTime, zone).instant(), notified));
        }
        return new Review(date, reviewTime.toString(), lookahead, minDogs, autoCancel, items);
    }

    /** The registrants the review has warned (N-16), by booking. */
    public List<Notified> riskNotified(ClassSession c) {
        var ids = c.risk() == null || c.risk().notifiedBookingIds() == null ? List.<String>of() : c.risk().notifiedBookingIds();
        return ids.isEmpty() ? List.of() : names(bookings.bookings(ids));
    }
    /** The registrants a club cancellation affected (N-08a). */
    public List<Notified> cancellationNotified(ClassSession c) { return names(bookings.clubCancelled(c.id())); }
    private List<Notified> names(List<ClassBookingsPort.BookingRef> refs) {
        return refs.stream().map(b -> {
            var person = census.person(b.memberId()).orElse(new SchedulingRecipients.Person("", null));
            return new Notified(person.firstName() == null ? "" : person.firstName(), person.gender(), census.dog(b.dogId()).map(SchedulingRecipients.Dog::name).orElse(""));
        }).toList();
    }
}
