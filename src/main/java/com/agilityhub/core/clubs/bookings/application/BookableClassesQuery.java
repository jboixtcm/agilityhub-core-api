package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.application.ports.*;
import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.ClassSessionBookingAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.*;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Service;

/**
 * S08 screen 04, `GET /me/bookable-classes?dogId=` (WP-08-D). Exactly one dog: the requested one, else
 * `Member.lastDogForClass` while still accessible, else the first own dog (R-08-23). The classes are the ACTIVE ones with
 * `startsAt > now` up to the end of W2 (R-08-04) admitted for the dog's level, minus those the dog has booked or waits for,
 * each with the server-decided R-08-03 state through {@link BookableRow} over {@link BookingEligibility},
 * {@link BookingLimits} (via {@link BookingChecks#limit}) and the class counters.
 * <p>The class list and its counters come from {@link BookableClassesCache} (S15 R-15-11: owned by S08, key
 * `{clubId}:{W0 key}`, TTL 30 s, warmed by P1); the per-dog part (exclusions, limits, pack, block, inactivity, leave) is
 * always read live. Class labels are cached for the same 30 s per class version and locale.
 */
@Service
public class BookableClassesQuery {
    private final BookingContext context; private final BookingMemberAccess census; private final MemberDogs dogs; private final BookableClassesCache base;
    private final BookingChecks checks; private final BookingRepository bookings; private final WaitlistEntryRepository waitlist;
    private final BookingViews views; private final PackBalancePort packs; private final InactivityPort inactivity;
    private final SingleClassChargePort charges; private final MemberActivityRowsPort activities;
    private final Cache<String, ClassSessionBookingAccess.Labels> labels;
    public BookableClassesQuery(BookingContext context, BookingMemberAccess census, MemberDogs dogs, BookableClassesCache base, BookingChecks checks,
            BookingRepository bookings, WaitlistEntryRepository waitlist, BookingViews views, PackBalancePort packs,
            InactivityPort inactivity, SingleClassChargePort charges, MemberActivityRowsPort activities, Clock clock) {
        this.context = context; this.census = census; this.dogs = dogs; this.base = base; this.checks = checks; this.bookings = bookings; this.waitlist = waitlist;
        this.views = views; this.packs = packs; this.inactivity = inactivity; this.charges = charges; this.activities = activities;
        this.labels = Caffeine.newBuilder().maximumSize(5000).expireAfterWrite(BookableClassesCache.TTL)
                .ticker(() -> TimeUnit.MILLISECONDS.toNanos(clock.millis())).build();
    }

    public Map<String, Object> bookable(String memberId, String dogId) {
        var member = census.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        var chips = dogs.chips(memberId);
        var chip = select(chips, dogId, member.lastDogForClass());
        var dog = chip.dog();
        var owner = census.member(dog.memberId()).orElseThrow(() -> new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE));
        var now = context.now(); var zone = context.zone(); var today = now.atZone(zone).toLocalDate();
        var out = new LinkedHashMap<String, Object>();
        out.put("dog", BookingViews.map("id", dog.id(), "name", dog.name(), "sex", dog.sex(), "levelId", dog.levelId(), "levelName", chip.levelName(), "own", chip.own()));
        out.put("dogs", chips.stream().map(MemberDogs.Chip::home).toList());
        var pack = context.enabled(Module.PACKS) ? packs.balance(owner.id(), dog.id()) : Optional.<PackBalancePort.Balance>empty();
        out.put("pack", pack.map(p -> BookingViews.map("planName", census.planName(owner.id(), LocaleContext.current()), "sessionsTotal", p.total(),
                "consumed", p.consumed(), "available", p.available(), "expiresOn", p.expiresOn(), "state", PackCard.state(p.available(), p.expiresOn(), today,
                        context.integer("billing.packLowBalanceSessions"), context.integer("billing.packExpiryWarningDays")))).orElse(null));
        var terms = context.enabled(Module.SINGLE_CLASS) ? charges.terms(owner.id()) : Optional.<SingleClassChargePort.Terms>empty();
        out.put("singleClass", terms.map(t -> BookingViews.map("chargeMode", t.mode(), "pricePerClass", t.price())).orElse(null));
        var blocked = member.blocked() ? member : owner.blocked() ? owner : null;
        out.put("bookingBlock", blocked == null ? null : Map.of("reason", Objects.toString(blocked.blockReason(), "")));
        out.put("activities", !context.enabled(Module.ACTIVITIES) ? List.of() : activities.bookable(memberId, dog.id()).stream()
                .map(a -> BookingViews.map("id", a.id(), "title", a.title(), "startsAtLocal", a.startsAtLocal(), "freeSeats", a.freeSeats())).toList());
        out.put("classes", classes(dog, owner, blocked != null, pack, terms, now));
        return out;
    }

    private static MemberDogs.Chip select(List<MemberDogs.Chip> chips, String dogId, String lastDogForClass) {
        if (dogId != null) { return chips.stream().filter(c -> c.id().equals(dogId)).findFirst().orElseThrow(() -> new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE)); }
        return chips.stream().filter(c -> c.id().equals(lastDogForClass)).findFirst()
                .or(() -> chips.stream().filter(MemberDogs.Chip::own).findFirst()).or(() -> chips.stream().findFirst())
                .orElseThrow(() -> new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE));
    }

    private List<Map<String, Object>> classes(BookingMemberAccess.Dog dog, BookingMemberAccess.Member owner, boolean blocked, Optional<PackBalancePort.Balance> pack,
            Optional<SingleClassChargePort.Terms> terms, Instant now) {
        var cached = base.get(); var weeks = context.weeks(); var zone = context.zone(); var locale = LocaleContext.current();
        var taken = new HashSet<String>();
        bookings.forDogs(List.of(dog.id()), BookingRepository.LIVE, now, cached.to()).forEach(b -> taken.add(b.classSessionId()));
        waitlist.liveForDogs(List.of(dog.id()), now).forEach(e -> taken.add(e.classSessionId()));
        boolean levelsEnabled = context.flag("levels.enabled"), waitlistOn = context.enabled(Module.WAITLIST), inactivityOn = context.enabled(Module.INACTIVITY);
        int waitlistMax = context.integer("waitlist.maxPerClass");
        var limits = new HashMap<String, BookingLimits.Result>();
        var result = new ArrayList<Map<String, Object>>();
        for (var s : cached.classes()) {
            if (!s.startsAt().isAfter(now) || taken.contains(s.id()) || !BookingEligibility.levelAllowed(levelsEnabled, dog.levelId(), s.levelIds())) { continue; }
            var week = weeks.week(s.startsAt()); var relative = weeks.relative(s.startsAt(), now); var date = s.startsAt().atZone(zone).toLocalDate();
            BookableRow.NotBookable reason = blocked ? BookableRow.NotBookable.BLOCKED
                    : BookingEligibility.leaving(owner.leaveDate(), s.startsAt(), zone) ? BookableRow.NotBookable.LEAVING
                    : inactivityOn && inactivity.covering(owner.id(), date).isPresent() ? BookableRow.NotBookable.INACTIVITY : null;
            boolean limitDone = relative != RelativeWeek.LATER
                    && limits.computeIfAbsent(week.key(), key -> checks.limit(key, relative, dog.id(), owner.id(), now)).done();
            var row = BookableRow.resolve(new BookableRow.Input(reason, relative == RelativeWeek.LATER,
                    pack.isPresent() && BookingEligibility.packEmpty(new BookingEligibility.Pack(pack.get().available(), pack.get().expiresOn()), date),
                    limitDone, s.booked() >= s.capacity(), waitlistOn, s.waiting(), waitlistMax));
            var label = com.agilityhub.core.shared.application.CacheLoads.get(labels,context.clubId() + ":" + s.id() + ":" + s.version() + ":" + locale.toLanguageTag(), key -> views.labels(s));
            var out = new LinkedHashMap<String, Object>();
            out.put("id", s.id()); out.put("startsAtLocal", views.local(s.startsAt())); out.put("endsAtLocal", views.local(s.endsAt()));
            out.put("description", label.description()); out.put("ringName", label.ringName()); out.put("ringColor", label.ringColor());
            out.put("week", relative); out.put("state", row.state()); out.put("notBookableReason", row.reason());
            out.put("freeSeats", Math.max(0, s.capacity() - s.booked()));
            out.put("waiting", waitlistOn ? s.waiting() : null); out.put("waitlistMax", waitlistOn ? waitlistMax : null);
            out.put("opensAt", row.state() == BookableRow.State.NOT_YET_OPEN ? weeks.opensAt(week) : null);
            out.put("price", terms.map(SingleClassChargePort.Terms::price).orElse(null));
            result.add(out);
        }
        return result;
    }
}
