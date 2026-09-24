package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess;
import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.scheduling.application.RingScheduleAccess;
import com.agilityhub.core.clubs.training.application.ports.RingSetupPort;
import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.clubs.training.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.LocaleContext;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * S09 R-09-02/03/04/15: the computed grid of `GET /training-slots`. Rings exist only when `active ∧
 * allowsFreeTraining`, with `capacity = Ring.trainingCapacity ?? training.capacityPerRingSlot`. The static layer
 * (days, slots, classes, blocks) comes from {@link TrainingGridCache}; bookings are always read live, in one query
 * for the whole range. MEMBER requests are clipped to the booking window, the other roles may ask ≤ 31 days.
 * INSTRUCTOR/ADMIN cells add `occupants[]`, `block` and `classSession`; with COURSES (and
 * `courses.showSetupToMembers` for a member) each ring carries its mounted `setup`.
 */
@Service
public class TrainingSlotService {
    static final int STAFF_MAX_DAYS = 31;
    public record Viewer(String memberId, boolean staff) { }
    private final TrainingContext context; private final PlanningCatalogAccess catalogs; private final RingScheduleAccess schedule;
    private final TrainingBookingRepository bookings; private final TrainingGridCache cache; private final TrainingEligibilityService eligibility;
    private final TrainingMemberAccess census; private final RingSetupPort setups;
    public TrainingSlotService(TrainingContext context, PlanningCatalogAccess catalogs, RingScheduleAccess schedule, TrainingBookingRepository bookings,
            TrainingGridCache cache, TrainingEligibilityService eligibility, TrainingMemberAccess census, RingSetupPort setups) {
        this.context = context; this.catalogs = catalogs; this.schedule = schedule; this.bookings = bookings; this.cache = cache;
        this.eligibility = eligibility; this.census = census; this.setups = setups;
    }

    /** R-09-02: the bookable rings in catalog order. */
    public List<PlanningCatalogAccess.TrainingRingView> bookableRings() {
        return catalogs.trainingRings().stream().filter(r -> r.active() && r.allowsFreeTraining()).toList();
    }
    public TrainingGrid.Ring ring(PlanningCatalogAccess.TrainingRingView r) { return new TrainingGrid.Ring(r.id(), context.capacity(r.id(), r.trainingCapacity())); }

    /** The static layer of one day (cached). */
    TrainingGridCache.Day day(LocalDate date) {
        return cache.day(context.clubId(), date, () -> {
            var slots = TrainingGrid.slots(date, context.openingHours().get(date.getDayOfWeek()), context.holidays().contains(date), context.zone(), context.slotMinutes());
            if (slots.isEmpty()) { return new TrainingGridCache.Day(date, true, List.of(), List.of(), List.of()); }
            var from = slots.getFirst().startsAt(); var to = slots.getLast().endsAt();
            return new TrainingGridCache.Day(date, false, slots, schedule.classes(from, to, null), schedule.blocks(from, to, false));
        });
    }

    /** Live cells of one slot for the given rings (the booking transaction never reads the cache). */
    public Map<String, TrainingGrid.Cell> liveCells(TrainingGrid.Slot slot, List<TrainingGrid.Ring> rings, String dogId) {
        var classes = schedule.classes(slot.startsAt(), slot.endsAt(), null).stream().map(c -> new TrainingGrid.Busy(c.id(), c.ringId(), c.from(), c.to())).toList();
        var blocks = schedule.blocks(slot.startsAt(), slot.endsAt(), false).stream().map(b -> new TrainingGrid.Busy(b.id(), b.ringId(), b.from(), b.to())).toList();
        var seats = seats(bookings.activeBetween(slot.startsAt(), slot.endsAt(), null));
        var result = new LinkedHashMap<String, TrainingGrid.Cell>();
        for (var ring : rings) { result.put(ring.id(), TrainingGrid.cell(ring, slot, classes, blocks, seats, dogId)); }
        return result;
    }
    static List<TrainingGrid.Seat> seats(List<TrainingBooking> active) {
        return active.stream().map(b -> new TrainingGrid.Seat(b.id(), b.ringId(), b.dogId(), b.startsAt(), b.endsAt(), b.seatIndex())).toList();
    }

    public Map<String, Object> grid(LocalDate from, LocalDate to, String dogId, String ringId, Viewer viewer) {
        if (from == null || to == null || to.isBefore(from)) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "to")); }
        var today = context.today(); int windowDays = context.windowDays(); var zone = context.zone(); var now = context.now();
        var start = from; var end = to;
        if (!viewer.staff()) {
            if (start.isBefore(today)) { start = today; }
            if (end.isAfter(today.plusDays(windowDays))) { end = today.plusDays(windowDays); }
        } else if (from.plusDays(STAFF_MAX_DAYS - 1L).isBefore(to)) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "to", "maxDays", STAFF_MAX_DAYS)); }
        boolean dogEligible = true;
        if (dogId != null) {
            var dog = viewer.staff() ? census.dog(dogId).orElseThrow(() -> new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE))
                    : census.reachableDogs(viewer.memberId()).stream().map(Map.Entry::getKey).filter(d -> d.id().equals(dogId)).findFirst()
                            .orElseThrow(() -> new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE));
            dogEligible = eligibility.canFreeTrain(dog);
        }
        var ringViews = bookableRings();
        if (ringId != null) {
            ringViews = ringViews.stream().filter(r -> r.id().equals(ringId)).toList();
            if (ringViews.isEmpty()) { throw new ApiException(ErrorCode.NOT_FOUND); }
        }
        var rings = ringViews.stream().map(this::ring).toList();
        var days = new ArrayList<TrainingGridCache.Day>();
        for (var date = start; !date.isAfter(end); date = date.plusDays(1)) { days.add(day(date)); }
        var open = days.stream().filter(d -> !d.closed()).toList();
        List<TrainingBooking> active = open.isEmpty() ? List.of()
                : bookings.activeBetween(open.getFirst().slots().getFirst().startsAt(), open.getLast().slots().getLast().endsAt(), null);
        var seats = seats(active);
        // Staff detail: class descriptions, block notes and occupants' names (never for a member, R-09-12).
        Map<String, String> descriptions = new HashMap<>(); Map<String, RingScheduleAccess.BlockInterval> staffBlocks = new HashMap<>();
        Map<String, String> memberNames = new HashMap<>(); Map<String, String> dogNames = new HashMap<>();
        if (viewer.staff() && !open.isEmpty()) {
            var rangeFrom = open.getFirst().slots().getFirst().startsAt(); var rangeTo = open.getLast().slots().getLast().endsAt();
            schedule.classes(rangeFrom, rangeTo, LocaleContext.current()).forEach(c -> descriptions.put(c.id(), c.description()));
            schedule.blocks(rangeFrom, rangeTo, true).forEach(b -> staffBlocks.put(b.id(), b));
            var names = census.firstNames(active.stream().map(TrainingBooking::memberId).distinct().toList());
            active.forEach(b -> memberNames.put(b.id(), Objects.toString(names.get(b.memberId()), "")));
            census.dogs(active.stream().map(TrainingBooking::dogId).distinct().toList()).forEach(d -> dogNames.put(d.id(), d.name()));
        }
        var dayViews = new ArrayList<Map<String, Object>>();
        for (var d : days) {
            var classes = d.classes().stream().map(c -> new TrainingGrid.Busy(c.id(), c.ringId(), c.from(), c.to())).toList();
            var blocks = d.blocks().stream().map(b -> new TrainingGrid.Busy(b.id(), b.ringId(), b.from(), b.to())).toList();
            var slotViews = new ArrayList<Map<String, Object>>();
            for (var slot : d.slots()) {
                var cells = new LinkedHashMap<String, Object>(); boolean anyFree = false;
                for (var ring : rings) {
                    var cell = TrainingGrid.cell(ring, slot, classes, blocks, seats, dogId);
                    anyFree |= cell.free();
                    cells.put(ring.id(), cell(cell, viewer, descriptions, staffBlocks, memberNames, dogNames));
                }
                var view = new LinkedHashMap<String, Object>();
                view.put("startsAt", slot.startsAt()); view.put("startsAtLocal", hhmm(slot.startsAt(), zone)); view.put("endsAtLocal", hhmm(slot.endsAt(), zone));
                view.put("anyFree", anyFree); view.put("bookable", TrainingRules.bookable(anyFree, slot.startsAt(), now, d.date(), today, windowDays));
                view.put("rings", cells); slotViews.add(view);
            }
            var view = new LinkedHashMap<String, Object>(); view.put("date", d.date()); view.put("closed", d.closed()); view.put("slots", slotViews); dayViews.add(view);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("timeZone", zone.getId()); out.put("slotMinutes", context.slotMinutes());
        out.put("window", Map.of("from", today, "to", today.plusDays(windowDays))); out.put("dogEligible", dogEligible);
        boolean setup = context.enabled(Module.COURSES) && (viewer.staff() || context.flag("courses.showSetupToMembers"));
        out.put("rings", ringViews.stream().map(r -> ringView(r, setup)).toList()); out.put("days", dayViews);
        return out;
    }
    private Map<String, Object> ringView(PlanningCatalogAccess.TrainingRingView r, boolean withSetup) {
        var out = new LinkedHashMap<String, Object>();
        out.put("id", r.id()); out.put("name", r.name()); out.put("shortName", r.shortName()); out.put("color", r.color()); out.put("order", r.order());
        out.put("capacity", context.capacity(r.id(), r.trainingCapacity()));
        if (withSetup && r.activeSetupId() != null) {
            setups.active(r.activeSetupId(), LocaleContext.current()).ifPresent(s -> {
                var setup = new LinkedHashMap<String, Object>(); setup.put("id", s.id()); setup.put("kind", s.kind()); setup.put("levelNames", s.levelNames());
                setup.put("builtAt", s.builtAt()); setup.put("expectedUntil", s.expectedUntil()); out.put("setup", setup);
            });
        }
        return out;
    }
    private static Map<String, Object> cell(TrainingGrid.Cell cell, Viewer viewer, Map<String, String> descriptions, Map<String, RingScheduleAccess.BlockInterval> blocks,
            Map<String, String> members, Map<String, String> dogs) {
        var out = new LinkedHashMap<String, Object>(); out.put("state", cell.state()); out.put("reason", cell.reason());
        if (cell.ownBookingId() != null) { out.put("bookingId", cell.ownBookingId()); }
        if (viewer.staff()) {
            out.put("occupants", cell.seats().stream().map(s -> occupant(s, members, dogs)).toList());
            cell.blocks().stream().findFirst().map(b -> blocks.get(b.id())).ifPresent(b -> {
                var block = new LinkedHashMap<String, Object>(); block.put("id", b.id()); block.put("kind", b.kind()); block.put("reason", b.reason());
                block.put("note", b.note()); block.put("createdByName", Objects.toString(b.createdByName(), "")); out.put("block", block);
            });
            cell.classes().stream().findFirst().ifPresent(c -> out.put("classSession", Map.of("id", c.id(), "description", Objects.toString(descriptions.get(c.id()), ""))));
        }
        return out;
    }
    /** @param members first name of the booking member by booking id */
    private static Map<String, Object> occupant(TrainingGrid.Seat seat, Map<String, String> members, Map<String, String> dogs) {
        return Map.of("bookingId", seat.bookingId(), "memberName", Objects.toString(members.get(seat.bookingId()), ""), "dogName", Objects.toString(dogs.get(seat.dogId()), ""));
    }
    static String hhmm(Instant instant, ZoneId zone) { return instant.atZone(zone).toLocalTime().withSecond(0).withNano(0).toString(); }
}
