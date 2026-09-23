package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S08 §6 wire forms as ordered maps (the controller converts them to the published DTOs): club-local `*Local`
 * strings, localized class labels, R-08-20 instructor visibility, calendar links and the derived `displayState`.
 */
@Service
public class BookingViews {
    static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
    private final BookingContext context; private final ClassSessionBookingAccess classes; private final BookingMemberAccess census;
    private final PackBalancePort packs; private final AttendanceStatePort attendance; private final BookingCalendarTokens tokens; private final CensusClubSettings clubs;
    public BookingViews(BookingContext context, ClassSessionBookingAccess classes, BookingMemberAccess census, PackBalancePort packs,
            AttendanceStatePort attendance, BookingCalendarTokens tokens, CensusClubSettings clubs) {
        this.context = context; this.classes = classes; this.census = census; this.packs = packs; this.attendance = attendance; this.tokens = tokens; this.clubs = clubs;
    }
    public String local(Instant instant) { return LOCAL.format(instant.atZone(context.zone())); }
    public ClassSessionBookingAccess.Labels labels(ClassSessionBookingAccess.Session s) { return classes.labels(s, LocaleContext.current()); }
    /** The class labels of a booking; empty labels if the class no longer exists (bookings are never deleted, BR-12). */
    public ClassSessionBookingAccess.Labels labelsOf(Booking b) { return labelsOf(b.classSessionId()); }
    private ClassSessionBookingAccess.Labels labelsOf(String classSessionId) {
        return classes.find(classSessionId).map(this::labels).orElse(new ClassSessionBookingAccess.Labels("", null, null, List.of(), ""));
    }

    public Map<String, Object> hold(SeatHoldService.Held held) {
        var s = held.subject().session(); var labels = labels(s); var out = new LinkedHashMap<String, Object>();
        out.put("id", held.hold().id()); out.put("classSessionId", s.id()); out.put("dogId", held.subject().dog().id());
        out.put("expiresAt", held.hold().expiresAt()); out.put("serverNow", held.now()); out.put("holdSeconds", held.holdSeconds());
        var cls = new LinkedHashMap<String, Object>(); cls.put("startsAtLocal", local(s.startsAt())); cls.put("endsAtLocal", local(s.endsAt()));
        cls.put("description", labels.description()); cls.put("levelNames", labels.levelNames()); cls.put("ringName", labels.ringName()); cls.put("ringColor", labels.ringColor());
        out.put("classSession", cls);
        var dog = held.subject().dog(); out.put("dog", map("id", dog.id(), "name", dog.name(), "sex", dog.sex()));
        out.put("limit", limit(held.limit(), held.subject().relative()));
        out.put("payment", held.payment().map(t -> map("mode", t.mode(), "price", t.price())).orElse(null));
        out.put("pack", held.subject().pack().map(p -> map("available", p.available(), "expiresOn", p.expiresOn())).orElse(null));
        return out;
    }
    public Map<String, Object> limit(BookingLimits.Result r, RelativeWeek week) {
        var out = new LinkedHashMap<String, Object>(); out.put("reached", r.reached()); out.put("unit", r.unit()); out.put("week", week);
        out.put("count", r.count()); out.put("max", r.max()); out.put("swappable", swappable(r)); out.put("notSelectable", notSelectable(r)); return out;
    }
    /** `details` of 409 BOOKING_LIMIT_REACHED (S08 §6): the limit fields with `swappable: []` and `nextBookableAt`. */
    public Map<String, Object> limitReached(BookingLimits.Result r, RelativeWeek week, Instant nextBookableAt) {
        var out = new LinkedHashMap<String, Object>(); out.put("unit", r.unit()); out.put("week", week); out.put("limit", r.max()); out.put("current", r.count());
        out.put("swappable", swappable(r)); out.put("notSelectable", notSelectable(r)); out.put("nextBookableAt", nextBookableAt.toString()); return out;
    }
    private List<Map<String, Object>> swappable(BookingLimits.Result r) {
        return r.swappable().stream().map(b -> {
            var labels = labelsOf(b.classSessionId());
            return map("bookingId", b.bookingId(), "startsAtLocal", local(b.classStartsAt()), "description", labels.description(), "ringName", labels.ringName());
        }).toList();
    }
    private List<Map<String, Object>> notSelectable(BookingLimits.Result r) {
        return r.notSelectable().stream().map(n -> {
            return map("bookingId", n.booking().bookingId(), "startsAtLocal", local(n.booking().classStartsAt()),
                    "description", labelsOf(n.booking().classSessionId()).description(), "reason", n.reason());
        }).toList();
    }

    public Map<String, Object> booking(Booking b, boolean staff, String checkoutUrl) {
        var labels = labelsOf(b); var now = context.now();
        var out = new LinkedHashMap<String, Object>();
        out.put("id", b.id()); out.put("state", b.state()); out.put("origin", b.origin()); out.put("classSessionId", b.classSessionId());
        out.put("dogId", b.dogId()); out.put("memberId", b.memberId());
        var visibility = InstructorVisibility.of(labels.instructorNames().isBlank() ? null : labels.instructorNames(), now, b.classStartsAt(),
                context.integer("bookings.showInstructorHoursBefore"), staff);
        var cls = new LinkedHashMap<String, Object>(); cls.put("startsAtLocal", local(b.classStartsAt())); cls.put("endsAtLocal", local(b.classEndsAt()));
        cls.put("description", labels.description()); cls.put("ringName", labels.ringName());
        cls.put("instructorName", visibility.instructorName()); cls.put("instructorVisibleAt", visibility.instructorVisibleAt());
        out.put("classSession", cls); out.put("bookedAt", b.bookedAt());
        // Every booking is created with `bookedBy` (R-08-19); only the display name may be empty.
        out.put("bookedBy", map("displayName", Objects.toString(b.bookedBy().displayName(), ""), "viaClub", b.origin() == BookingOrigin.BACKOFFICE));
        out.put("swapFromBookingId", b.swapFromBookingId());
        out.put("pack", context.enabled(Module.PACKS) ? packs.balance(b.memberId(), b.dogId()).map(p -> map("available", p.available(), "expiresOn", p.expiresOn())).orElse(null) : null);
        out.put("charge", b.charge() == null || !context.enabled(Module.SINGLE_CLASS) ? null : map("mode", b.charge().mode(), "price", b.charge().price(), "paidAt", b.charge().paidAt()));
        out.put("checkoutUrl", b.state() == BookingState.PAYMENT_PENDING ? checkoutUrl : null);
        out.put("calendarLinks", calendarLinks(b, labels));
        // A cancellation always records `cancelledBy`, `late` and `minutesBefore` together with `cancelledAt`.
        out.put("cancellation", b.cancelledAt() == null ? null : map("at", b.cancelledAt(), "byDisplayName", Objects.toString(b.cancelledBy().displayName(), ""),
                "byRole", b.cancelledBy().role(), "late", b.late(), "minutesBefore", b.minutesBefore(), "message", b.cancelMessage()));
        out.put("displayState", BookingDisplay.of(b.state(), b.classEndsAt(), now, attendance.noShow(b.id())));
        return out;
    }
    public Map<String, Object> calendarLinks(Booking b, ClassSessionBookingAccess.Labels labels) {
        var event = event(b, labels);
        String path = "/api/v1/bookings/" + b.id() + "/calendar.ics?token=" + tokens.issue(b.id(), b.classEndsAt());
        String host;
        try { host = "https://" + clubs.appHost(); } catch (ApiException noVerifiedHost) { host = ""; }
        return map("google", BookingCalendar.google(event), "outlook", BookingCalendar.outlook(event), "ics", host + path);
    }
    public BookingCalendar.Event event(Booking b, ClassSessionBookingAccess.Labels labels) {
        String title = labels.description() + census.dog(b.dogId()).map(d -> " · " + d.name()).orElse("");
        return new BookingCalendar.Event(b.id() + "@" + b.clubId() + ".agilityhub", title, Objects.toString(labels.ringName(), ""),
                b.classStartsAt(), b.classEndsAt(), b.bookedAt());
    }
    public Map<String, Object> listItem(Booking b, Map<String, BookingMemberAccess.Dog> dogs, Map<String, String> members) {
        var dog = dogs.get(b.dogId()); var out = new LinkedHashMap<String, Object>();
        out.put("id", b.id()); out.put("state", b.state()); out.put("origin", b.origin()); out.put("classSessionId", b.classSessionId());
        out.put("classStartsAt", b.classStartsAt()); out.put("bookingWeekKey", b.bookingWeekKey()); out.put("dogId", b.dogId());
        out.put("dogName", dog == null ? "" : dog.name()); out.put("memberId", b.memberId()); out.put("memberName", members.getOrDefault(b.memberId(), ""));
        out.put("bookedAt", b.bookedAt()); out.put("late", b.late()); return out;
    }
    static Map<String, Object> map(Object... entries) {
        var out = new LinkedHashMap<String, Object>(); for (int i = 0; i < entries.length; i += 2) { out.put((String) entries[i], entries[i + 1]); } return out;
    }
}
