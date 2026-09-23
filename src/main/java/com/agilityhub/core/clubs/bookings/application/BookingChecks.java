package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * The shared S08 check pipeline of hold, confirmation, waiting-list join and claim: load the class, the dog, its
 * owner and the booker, run {@link BookingEligibility} (R-08-04/05/06/17), reject a second live booking of the dog
 * (ALREADY_BOOKED), then evaluate the weekly limit of the class's booking week (R-08-02/03/09).
 */
@Service
public class BookingChecks {
    public record Subject(ClassSessionBookingAccess.Session session, BookingMemberAccess.Dog dog, BookingMemberAccess.Member owner,
            BookingMemberAccess.Member booker, BookingWeeks.Week week, RelativeWeek relative, Optional<PackBalancePort.Balance> pack) { }
    private final BookingContext context; private final ClassSessionBookingAccess classes; private final BookingMemberAccess census;
    private final BookingRepository bookings; private final InactivityPort inactivity; private final PackBalancePort packs;
    public BookingChecks(BookingContext context, ClassSessionBookingAccess classes, BookingMemberAccess census, BookingRepository bookings,
            InactivityPort inactivity, PackBalancePort packs) {
        this.context = context; this.classes = classes; this.census = census; this.bookings = bookings; this.inactivity = inactivity; this.packs = packs;
    }

    public Subject subject(BookingActor actor, String classSessionId, String dogId, Instant now) {
        var session = classes.find(classSessionId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var dog = census.dog(dogId).filter(d -> actor.memberId() != null && census.canAccess(actor.memberId(), d))
                .orElseThrow(() -> new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE));
        var owner = census.member(dog.memberId()).orElseThrow(() -> new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE));
        var booker = census.member(actor.memberId()).orElseThrow(() -> new ApiException(ErrorCode.MEMBER_NOT_ACTIVE));
        var weeks = context.weeks(); var week = weeks.week(session.startsAt()); var relative = weeks.relative(session.startsAt(), now);
        var localDate = session.startsAt().atZone(context.zone()).toLocalDate();
        var pack = context.enabled(Module.PACKS) ? packs.balance(owner.id(), dog.id()) : Optional.<PackBalancePort.Balance>empty();
        var period = context.enabled(Module.INACTIVITY) ? inactivity.covering(owner.id(), localDate).map(p -> new BookingEligibility.Period(p.from(), p.to()))
                : Optional.<BookingEligibility.Period>empty();
        BookingEligibility.check(new BookingEligibility.Input(true, dog.active(), person(owner), person(booker), period,
                context.flag("levels.enabled"), dog.levelId(), session.levelIds(), session.active(), session.startsAt(), context.zone(), now,
                relative, weeks.opensAt(week), pack.map(p -> new BookingEligibility.Pack(p.available(), p.expiresOn()))));
        return new Subject(session, dog, owner, booker, week, relative, pack);
    }
    public void notBookedYet(Subject s) {
        if (bookings.live(s.session().id(), s.dog().id()).isPresent()) { throw new ApiException(ErrorCode.ALREADY_BOOKED); }
    }
    public BookingLimits.Result limit(Subject s, Instant now) {
        var unit = context.unit();
        var week = bookings.week(s.week().key(), unit == LimitUnit.DOG ? "dogId" : "memberId", unit == LimitUnit.DOG ? s.dog().id() : s.owner().id()).stream()
                .map(BookingChecks::counted).toList();
        int max = BookingLimits.max(s.relative(), context.integer("bookings.maxCurrentWeek"), context.integer("bookings.maxNextWeek"));
        return BookingLimits.evaluate(week, unit, s.dog().id(), s.owner().id(), max, now, context.lateThreshold());
    }
    static BookingLimits.Counted counted(Booking b) { return new BookingLimits.Counted(b.id(), b.classSessionId(), b.dogId(), b.memberId(), b.state(), b.classStartsAt()); }
    private static BookingEligibility.Person person(BookingMemberAccess.Member m) {
        return new BookingEligibility.Person(m.status(), m.blocked(), m.blockReason(), m.leaveDate());
    }
}
