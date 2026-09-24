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
        var rows = rows(requested); var locale = LocaleContext.current();
        var items = rows.rows().stream().map(r -> new Item(r.session().id(), r.session().date(), r.dayLabel(), r.session().startTime(),
                projection.description(r.session(), locale), r.ringName(), r.bookedCount(), r.status(), r.cancelledAt(), r.reviewAt(), r.notified())).toList();
        return new Review(rows.date(), rows.reviewTime(), rows.lookaheadDays(), rows.minDogs(), rows.autoCancelSameDay(), items);
    }

    /** A form-A row before localization. S14's D1 card maps these same rows: the statuses are computed only here. */
    public record Row(ClassSession session, String dayLabel, String ringName, int bookedCount, String status, Instant cancelledAt, Instant reviewAt,
            List<Notified> notified) { }
    public record Rows(LocalDate date, String reviewTime, int lookaheadDays, int minDogs, boolean autoCancelSameDay, List<Row> rows) { }

    public Rows rows(LocalDate requested) {
        var config = context.config(); var zone = projection.zone(); var now = clock.instant();
        var date = requested == null ? now.atZone(zone).toLocalDate() : requested;
        int lookahead = config.get("classes.riskLookaheadDays", Integer.class); int minDogs = config.get("classes.minDogs", Integer.class);
        boolean autoCancel = config.get("classes.riskAutoCancelSameDay", Boolean.class);
        var reviewTime = LocalTime.parse(config.get("classes.riskReviewTime", String.class));
        var catalog = context.catalog();
        var window = classes.startingBetween(date.atStartOfDay(zone).toInstant(), date.plusDays(lookahead + 1L).atStartOfDay(zone).toInstant());
        // E5-T10: every read below is one batch over the window's classes, never one read per class or per registrant.
        var cancelled = window.stream().filter(RiskReviewQuery::cancelledByReview).map(ClassSession::id).toList();
        // A class that has begun is out of the review's reach (RiskReviewJob's skippedStarted): nothing will act on it.
        var candidates = window.stream().filter(c -> c.state() == ClassState.ACTIVE && !(c.risk() != null && c.risk().exempt()) && c.startsAt().isAfter(now))
                .map(ClassSession::id).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        var affected = cancelled.isEmpty() ? Map.<String, List<ClassBookingsPort.BookingRef>>of() : bookings.clubCancelledByClass(cancelled);
        var live = candidates.isEmpty() ? Map.<String, List<ClassBookingsPort.BookingRef>>of() : bookings.activeBookingsByClass(candidates);
        var atRisk = window.stream().filter(c -> candidates.contains(c.id()) && live.getOrDefault(c.id(), List.of()).size() < minDogs).toList();
        var atRiskIds = atRisk.stream().map(ClassSession::id).collect(java.util.stream.Collectors.toSet());
        var warnedIds = atRisk.stream().flatMap(c -> notifiedIds(c).stream()).distinct().toList();
        var warned = new HashMap<String, ClassBookingsPort.BookingRef>();
        if (!warnedIds.isEmpty()) { bookings.bookings(warnedIds).forEach(b -> warned.put(b.bookingId(), b)); }
        var refs = new ArrayList<ClassBookingsPort.BookingRef>(warned.values()); affected.values().forEach(refs::addAll);
        var people = census.people(refs.stream().map(ClassBookingsPort.BookingRef::memberId).collect(java.util.stream.Collectors.toSet()));
        var dogs = census.dogs(refs.stream().map(ClassBookingsPort.BookingRef::dogId).collect(java.util.stream.Collectors.toSet()));
        java.util.function.Function<List<ClassBookingsPort.BookingRef>, List<Notified>> names = list -> list.stream().map(b -> {
            var person = people.getOrDefault(b.memberId(), new SchedulingRecipients.Person("", null));
            var dog = dogs.get(b.dogId());
            return new Notified(person.firstName() == null ? "" : person.firstName(), person.gender(), dog == null ? "" : dog.name());
        }).toList();
        var rows = new ArrayList<Row>();
        for (ClassSession c : window) {
            String label = c.date().equals(date) ? "TODAY" : c.date().equals(date.plusDays(1)) ? "TOMORROW" : "OTHER";
            String ring = catalog.rings().stream().filter(r -> r.id().equals(c.ringId())).map(SchedulingCatalog.Resource::name).findFirst().orElse(null);
            if (cancelledByReview(c)) {
                rows.add(new Row(c, label, ring, c.cancellation().affectedBookings(), "AUTO_CANCELLED", c.cancellation().at(), null,
                        names.apply(affected.getOrDefault(c.id(), List.of()))));
                continue;
            }
            if (!atRiskIds.contains(c.id())) { continue; }
            int booked = live.getOrDefault(c.id(), List.of()).size();
            // The registrants the review has warned (N-16), in the order of the mark.
            var notified = names.apply(notifiedIds(c).stream().distinct().map(warned::get).filter(Objects::nonNull).toList());
            boolean wasWarned = !notified.isEmpty() || c.risk() != null && c.risk().adminNotifiedAt() != null;
            // AT_RISK = warned registrants; otherwise the review will act (WILL_CANCEL), or the club decides (WILL_REVIEW).
            String status = wasWarned && booked > 0 ? "AT_RISK" : autoCancel ? "WILL_CANCEL" : "WILL_REVIEW";
            rows.add(new Row(c, label, ring, booked, status, null, WeekCalendarRules.resolve(c.date(), reviewTime, zone).instant(), notified));
        }
        return new Rows(date, reviewTime.toString(), lookahead, minDogs, autoCancel, List.copyOf(rows));
    }

    private static boolean cancelledByReview(ClassSession c) {
        return c.state() == ClassState.CANCELLED && c.cancellation() != null && c.cancellation().reason() == ClassCancellationReason.RISK_REVIEW;
    }
    private static List<String> notifiedIds(ClassSession c) {
        return c.risk() == null || c.risk().notifiedBookingIds() == null ? List.of() : c.risk().notifiedBookingIds();
    }
}
