package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.messaging.application.NotificationFeedCounts;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S08 screen 03, `GET /me/home?dogId=` (WP-08-D): the dog chips, the R-08-02 counters of W0 and W1 summed over the
 * filtered dogs, and one chronological list of the future rows (`endsAt > now`) of four sources — class bookings
 * (`CLASS`), waiting-list entries (`CLASS_WAITLIST`), free-training bookings (`TRAINING`, {@link MemberTrainingRowsPort})
 * and activity registrations (`ACTIVITY`, {@link MemberActivityRowsPort}). Everything is decided here (R-08-20
 * instructor visibility included); module off removes its rows (§9). Activity registrations belong to the member, not
 * to a dog, so the dog filter keeps them.
 */
@Service
public class MemberHomeQuery {
    private final BookingContext context; private final BookingMemberAccess census; private final MemberDogs dogs; private final BookingRepository bookings;
    private final WaitlistEntryRepository waitlist; private final ClassSessionBookingAccess classes; private final BookingViews views;
    private final MemberTrainingRowsPort training; private final MemberActivityRowsPort activities; private final NotificationFeedCounts feed;
    private final IcuMessageSource messages;
    public MemberHomeQuery(BookingContext context, BookingMemberAccess census, MemberDogs dogs, BookingRepository bookings, WaitlistEntryRepository waitlist,
            ClassSessionBookingAccess classes, BookingViews views, MemberTrainingRowsPort training, MemberActivityRowsPort activities,
            NotificationFeedCounts feed, IcuMessageSource messages) {
        this.context = context; this.census = census; this.dogs = dogs; this.bookings = bookings; this.waitlist = waitlist; this.classes = classes;
        this.views = views; this.training = training; this.activities = activities; this.feed = feed; this.messages = messages;
    }
    private record Entry(Instant startsAt, Map<String, Object> row) { }

    public Map<String, Object> home(String memberId, String dogId) {
        var member = census.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var chips = dogs.chips(memberId); var names = new HashMap<String, String>(); chips.forEach(c -> names.put(c.id(), c.dog().name()));
        if (dogId != null && !names.containsKey(dogId)) { throw new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE); }
        List<String> filtered = dogId == null ? chips.stream().map(MemberDogs.Chip::id).toList() : List.of(dogId);
        var now = context.now(); var locale = LocaleContext.current();
        var out = new LinkedHashMap<String, Object>();
        out.put("member", BookingViews.map("id", member.id(), "firstName", member.firstName(), "gender", census.gender(member.id())));
        out.put("dogs", chips.stream().map(MemberDogs.Chip::home).toList());
        out.put("selectedDogId", dogId);
        out.put("limits", limits(filtered, now));
        var rows = new ArrayList<Entry>();
        classRows(filtered, names, now, locale, rows);
        if (context.enabled(Module.WAITLIST)) { waitlistRows(filtered, names, now, locale, rows); }
        if (context.enabled(Module.FREE_TRAINING)) {
            String title = messages.format("bookings.home.trainingTitle", Map.of(), locale);
            for (var t : training.upcoming(memberId, filtered, now)) {
                rows.add(new Entry(t.startsAt(), row("TRAINING", t.id(), "CONFIRMED", t.dogId(), names.get(t.dogId()), title, t.startsAt(),
                        views.local(t.startsAt()), views.local(t.endsAt()), t.ringName(), null, null)));
            }
        }
        if (context.enabled(Module.ACTIVITIES)) {
            for (var a : activities.live(memberId)) {
                rows.add(new Entry(a.startsAt(), row("ACTIVITY", a.id(), a.state(), null, null, a.title(), a.startsAt(), a.startsAtLocal(), a.endsAtLocal(),
                        a.placeLabel(), null, null)));
            }
        }
        rows.sort(Comparator.comparing(Entry::startsAt).thenComparing(e -> e.row().get("type").toString()).thenComparing(e -> e.row().get("id").toString()));
        out.put("reservations", rows.stream().map(Entry::row).toList());
        out.put("history", Map.of("monthsVisible", context.integer("history.monthsVisible")));
        out.put("notifications", Map.of("unreadCount", feed.unreadApp(member.accountId())));
        var user = CurrentUser.current();
        out.put("impersonation", user != null && user.impersonation() != null ? Map.of("actorName", Objects.toString(user.impersonation().actorName(), "")) : null);
        return out;
    }

    /** R-08-02 counters of 03: the counted bookings (ACTIVE, PAYMENT_PENDING, CANCELLED_LATE) of the filtered dogs in W0 and W1. */
    private Map<String, Object> limits(List<String> dogIds, Instant now) {
        var weeks = context.weeks(); var current = weeks.week(now); var next = weeks.week(current.end());
        var counts = new HashMap<String, Integer>();
        if (!dogIds.isEmpty()) {
            bookings.forDogs(dogIds, BookingLimits.COUNTED, current.start(), next.end()).forEach(b -> counts.merge(b.bookingWeekKey(), 1, Integer::sum));
        }
        return BookingViews.map("unit", context.unit(),
                "currentWeek", BookingViews.map("count", counts.getOrDefault(current.key(), 0), "max", context.integer("bookings.maxCurrentWeek"), "weekKey", current.key()),
                "nextWeek", BookingViews.map("count", counts.getOrDefault(next.key(), 0), "max", context.integer("bookings.maxNextWeek"), "weekKey", next.key()));
    }
    private void classRows(List<String> dogIds, Map<String, String> names, Instant now, Locale locale, List<Entry> rows) {
        if (dogIds.isEmpty()) { return; }
        int hours = context.integer("bookings.showInstructorHoursBefore");
        // A class lasts well under a day: starting after now − 1 day covers every class still running.
        for (var b : bookings.forDogs(dogIds, BookingRepository.LIVE, now.minusSeconds(86_400), null)) {
            if (!b.classEndsAt().isAfter(now)) { continue; }
            var labels = views.labelsOf(b);
            var visibility = InstructorVisibility.of(labels.instructorNames() == null || labels.instructorNames().isBlank() ? null : labels.instructorNames(),
                    now, b.classStartsAt(), hours, false);
            rows.add(new Entry(b.classStartsAt(), row("CLASS", b.id(), b.state() == BookingState.PAYMENT_PENDING ? "PAYMENT_PENDING" : "CONFIRMED", b.dogId(),
                    names.get(b.dogId()), title(labels.description(), locale), b.classStartsAt(), views.local(b.classStartsAt()), views.local(b.classEndsAt()),
                    labels.ringName(), visibility.instructorName(), visibility.instructorVisibleAt())));
        }
    }
    private void waitlistRows(List<String> dogIds, Map<String, String> names, Instant now, Locale locale, List<Entry> rows) {
        for (var e : waitlist.liveForDogs(dogIds, now)) {
            var labels = classes.find(e.classSessionId()).map(views::labels).orElse(null);
            rows.add(new Entry(e.classStartsAt(), row("CLASS_WAITLIST", e.id(), "WAITLISTED", e.dogId(), names.get(e.dogId()),
                    title(labels == null ? "" : labels.description(), locale), e.classStartsAt(), views.local(e.classStartsAt()), null,
                    labels == null ? null : labels.ringName(), null, null)));
        }
    }
    private String title(String description, Locale locale) {
        return messages.format("bookings.home.classTitle", Map.of("description", Objects.toString(description, "")), locale);
    }
    private static Map<String, Object> row(String type, String id, String state, String dogId, String dogName, String title, Instant startsAt,
            String startsAtLocal, String endsAtLocal, String ringName, String instructorName, Instant instructorVisibleAt) {
        return BookingViews.map("type", type, "id", id, "state", state, "dogId", dogId, "dogName", dogName, "title", title, "startsAt", startsAt,
                "startsAtLocal", startsAtLocal, "endsAtLocal", endsAtLocal, "ringName", ringName, "instructorName", instructorName,
                "instructorVisibleAt", instructorVisibleAt);
    }
}
