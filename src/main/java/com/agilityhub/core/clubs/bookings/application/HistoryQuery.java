package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.ActivityHistoryQuery;
import com.agilityhub.core.clubs.bookings.application.ports.TrainingHistoryQuery;
import com.agilityhub.core.clubs.bookings.domain.AttendanceState;
import com.agilityhub.core.clubs.bookings.domain.HistoryRules;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.bookings.application.BookingViews.map;

/**
 * S10 R-10-14 `GET /me/history` (25): the items of the dogs the member reaches (own, plus the family group's with
 * FAMILY_GROUP) whose start is at or after the local day `history.monthsVisible` months ago, newest first, at most 500
 * with no paging (assumption). Live bookings are on 03: a class booking appears once its class has started (ACTIVE) or
 * as soon as it is cancelled, future ones included (§13-14); `state`, `counts` and `detail.kind` per
 * {@link HistoryRules}. Trainings (S09, FREE_TRAINING): done (ACTIVE and ended), CANCELLED and CANCELLED_BY_CLUB.
 * Activities (S07, ACTIVITIES) belong to the member, not to a dog: they are listed with «Tots» only (assumption).
 */
@Service
public class HistoryQuery {
    static final int MAX_ITEMS = 500;
    public enum Type { CLASS, TRAINING, ACTIVITY }
    private final BookingContext context; private final BookingMemberAccess members; private final BookingRepository bookings;
    private final AttendanceRepository attendances; private final ClassSessionBookingAccess classes; private final PlanningCatalogAccess catalogs;
    private final TrainingHistoryQuery trainings; private final ActivityHistoryQuery activities; private final IcuMessageSource messages;
    public HistoryQuery(BookingContext context, BookingMemberAccess members, BookingRepository bookings, AttendanceRepository attendances,
            ClassSessionBookingAccess classes, PlanningCatalogAccess catalogs, TrainingHistoryQuery trainings, ActivityHistoryQuery activities,
            IcuMessageSource messages) {
        this.context = context; this.members = members; this.bookings = bookings; this.attendances = attendances; this.classes = classes;
        this.catalogs = catalogs; this.trainings = trainings; this.activities = activities; this.messages = messages;
    }

    public Map<String, Object> history(String memberId, String dogId, Type type) {
        if (memberId == null) { throw new ApiException(ErrorCode.NO_MEMBERSHIP); } // a club token without a member has no history
        var zone = context.zone(); var now = context.now(); var locale = LocaleContext.current();
        int months = context.integer("history.monthsVisible");
        var fromDate = context.today().minusMonths(months); var from = fromDate.atStartOfDay(zone).toInstant();
        var dogs = members.accessibleDogs(memberId).stream().filter(d -> !"PENDING".equals(d.status()))
                .sorted(Comparator.comparing((BookingMemberAccess.Dog d) -> !memberId.equals(d.memberId())).thenComparing(BookingMemberAccess.Dog::name)
                        .thenComparing(BookingMemberAccess.Dog::id)).toList();
        if (dogId != null && dogs.stream().noneMatch(d -> d.id().equals(dogId))) { throw new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE); }
        var selected = dogId == null ? dogs.stream().map(BookingMemberAccess.Dog::id).toList() : List.of(dogId);
        var names = new HashMap<String, String>(); dogs.forEach(d -> names.put(d.id(), d.name()));
        var owners = new HashMap<String, String>();
        members.members(dogs.stream().map(BookingMemberAccess.Dog::memberId).filter(m -> !m.equals(memberId)).distinct().toList())
                .forEach(m -> owners.put(m.id(), m.firstName()));
        var levels = context.flag("levels.enabled") ? catalogs.levelRefs() : Map.<String, PlanningCatalogAccess.LevelRef>of();
        var types = new ArrayList<Type>(); types.add(Type.CLASS);
        if (context.enabled(Module.FREE_TRAINING)) { types.add(Type.TRAINING); }
        if (context.enabled(Module.ACTIVITIES)) { types.add(Type.ACTIVITY); }
        var items = new ArrayList<Map<String, Object>>();
        if (type == null || type == Type.CLASS) { items.addAll(classItems(selected, names, from, now, zone, locale)); }
        if (types.contains(Type.TRAINING) && (type == null || type == Type.TRAINING)) {
            String title = messages.format("bookings.home.trainingTitle", Map.of(), locale);
            for (var t : trainings.itemsFor(selected, from)) {
                String state = switch (t.state()) { case "ACTIVE" -> t.endsAt().isAfter(now) ? null : "DONE"; case "CANCELLED", "CANCELLED_BY_CLUB" -> t.state(); default -> null; };
                if (state == null) { continue; } // live: on 03
                items.add(item("TRAINING", t.id(), t.startsAt(), zone, title, t.dogId(), names.get(t.dogId()), state, null,
                        "CANCELLED".equals(state) ? map("kind", "BY_MEMBER", "at", null, "atLocal", null, "message", null) : null));
            }
        }
        if (types.contains(Type.ACTIVITY) && dogId == null && (type == null || type == Type.ACTIVITY)) {
            for (var a : activities.itemsFor(memberId, from)) {
                items.add(item("ACTIVITY", a.id(), a.startsAt(), zone, a.title(), null, null, a.state(), null, null));
            }
        }
        items.sort(Comparator.comparing((Map<String, Object> m) -> (Instant) m.get("startsAt")).reversed().thenComparing(m -> m.get("id").toString()));
        var list = items.stream().limit(MAX_ITEMS).toList();
        list.forEach(m -> m.remove("startsAt")); // the sort key only; the wire carries `date` and `startsAtLocal`
        return map("monthsVisible", months, "from", fromDate, "showDog", dogs.size() > 1,
                "dogs", dogs.stream().map(d -> {
                    boolean own = memberId.equals(d.memberId());
                    var level = d.levelId() == null ? null : levels.get(d.levelId());
                    return map("id", d.id(), "name", d.name(), "levelCode", level == null ? null : level.code(), "own", own, "ownerFirstName", own ? null : owners.get(d.memberId()));
                }).toList(), "types", types, "items", list);
    }

    private List<Map<String, Object>> classItems(List<String> dogIds, Map<String, String> names, Instant from, Instant now, ZoneId zone, Locale locale) {
        if (dogIds.isEmpty()) { return List.of(); }
        var found = bookings.forDogs(dogIds, List.of(), from, null);
        var marks = attendances.byBookings(found.stream().map(Booking::id).toList());
        var entries = new ArrayList<Map.Entry<Booking, HistoryRules.Entry>>();
        for (var b : found) {
            var a = marks.get(b.id());
            var input = new HistoryRules.ClassBooking(b.state(), b.cancelReason(), b.cancelledBy() != null && b.cancelledBy().impersonatedMemberId() != null,
                    b.classStartsAt(), b.cancelledAt(), a == null ? AttendanceState.PENDING : a.state(), a != null && a.notice() != null && a.notice().afterClassEnd(),
                    a == null || a.notice() == null ? null : a.notice().at());
            HistoryRules.classEntry(input, now).ifPresent(e -> entries.add(Map.entry(b, e)));
        }
        var labels = classes.labels(entries.stream().map(e -> e.getKey().classSessionId()).distinct().toList(), locale);
        return entries.stream().map(e -> {
            var b = e.getKey(); var entry = e.getValue(); var label = labels.get(b.classSessionId());
            String title = messages.format("bookings.home.classTitle", Map.of("description", label == null ? "" : label.description()), locale);
            Map<String, Object> detail = entry.kind() == null ? null : map("kind", entry.kind(), "at", entry.at(),
                    "atLocal", entry.at() == null ? null : InstructorDayQuery.hhmm(entry.at(), zone),
                    "message", entry.kind() == HistoryRules.DetailKind.BY_CLUB ? b.cancelMessage() : null);
            return item("CLASS", b.id(), b.classStartsAt(), zone, title, b.dogId(), names.get(b.dogId()), entry.state().name(), entry.counts(), detail);
        }).toList();
    }
    private static Map<String, Object> item(String type, String id, Instant startsAt, ZoneId zone, String title, String dogId, String dogName, String state,
            Boolean counts, Map<String, Object> detail) {
        var local = startsAt.atZone(zone).toLocalDateTime().withSecond(0).withNano(0);
        return map("type", type, "id", id, "date", local.toLocalDate(), "startsAtLocal", local.toString(), "title", title, "dogId", dogId, "dogName", dogName,
                "state", state, "counts", counts, "detail", detail, "startsAt", startsAt);
    }
}
