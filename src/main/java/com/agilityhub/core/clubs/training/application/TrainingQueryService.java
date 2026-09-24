package com.agilityhub.core.clubs.training.application;

import com.agilityhub.core.clubs.bookings.application.ports.InactivityPort;
import com.agilityhub.core.clubs.census.application.TrainingMemberAccess;
import com.agilityhub.core.clubs.training.domain.*;
import com.agilityhub.core.clubs.training.persistence.*;
import com.agilityhub.core.identity.application.CensusIdentityService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import java.time.*;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * S09 reads: `GET /me/training-summary` (R-09-05/09), the booking detail (member own or family group, instructor,
 * admin — another member's booking is 404), `GET /me/training-bookings` with `cancellableUntil` per row, and the
 * universal list `training-bookings` (ring usage register, ADMIN/INSTRUCTOR) with its export.
 */
@Service
public class TrainingQueryService implements ListProvider {
    static final List<String> FILTERS = List.of("date", "ringId", "memberId", "dogId", "state", "origin");
    static final List<String> COLUMNS = List.of("date", "startsAtLocal", "ringName", "memberName", "dogName", "state", "origin", "createdAt");
    static final List<String> FIELDS = List.of("id", "date", "startsAt", "startsAtLocal", "ringId", "ringName", "memberId", "memberName", "dogId", "dogName", "state", "origin", "createdAt");
    private final TrainingContext context; private final TrainingBookingRepository bookings; private final TrainingMemberAccess census;
    private final TrainingEligibilityService eligibility; private final TrainingBookingService service; private final InactivityPort inactivity;
    private final CensusIdentityService identities;
    public TrainingQueryService(TrainingContext context, TrainingBookingRepository bookings, TrainingMemberAccess census, TrainingEligibilityService eligibility,
            TrainingBookingService service, InactivityPort inactivity, CensusIdentityService identities) {
        this.context = context; this.bookings = bookings; this.census = census; this.eligibility = eligibility; this.service = service;
        this.inactivity = inactivity; this.identities = identities;
    }

    /** R-09-05/09: eligible dogs, default dog, and the counter of the training week of `date` (today: the week of now). */
    public Map<String, Object> summary(String memberId, String dogId, LocalDate date) {
        var member = census.member(memberId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (dogId != null && census.reachableDogs(memberId).stream().noneMatch(e -> e.getKey().id().equals(dogId))) { throw new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE); }
        var eligible = eligibility.eligibleDogs(memberId); var now = context.now(); var today = context.today(); var zone = context.zone();
        var dogs = eligible.stream().map(e -> {
            var out = new LinkedHashMap<String, Object>(); out.put("id", e.dog().id()); out.put("name", e.dog().name()); out.put("levelName", e.levelName());
            out.put("ownerName", e.own() || !census.familyGroups() ? null : e.owner().firstName()); out.put("rightSource", e.source()); return (Map<String, Object>) out;
        }).toList();
        String defaultDog = eligible.stream().map(e -> e.dog().id()).filter(id -> id.equals(member.lastDogForTraining())).findFirst()
                .orElse(eligible.isEmpty() ? null : eligible.getFirst().dog().id());
        var day = date == null ? today : date;
        Instant reference = day.equals(today) ? now : day.atStartOfDay(zone).toInstant();
        String unitDog = dogId != null ? dogId : defaultDog;
        var counter = service.counter(unitDog, memberId, reference); var current = context.week(now);
        var week = service.counted(context.dogUnit(), unitDog, memberId, counter.weekStart());
        var opens = context.weekOpensAt();
        var out = new LinkedHashMap<String, Object>();
        out.put("eligibleDogs", dogs); out.put("defaultDogId", defaultDog); out.put("limitUnit", context.limitUnit());
        out.put("weekOpensAt", Map.of("dayOfWeek", opens.get("dayOfWeek").toString(), "time", opens.get("time").toString()));
        out.put("week", Map.of("start", counter.weekStart(), "end", counter.weekEnd(), "current", counter.weekStart().equals(current.start())));
        out.put("counter", counter(counter)); out.put("cancellableBookings", service.cancellable(week, now));
        out.put("bookingBlock", member.blocked() ? Map.of("reason", Objects.toString(member.blockReason(), "")) : null);
        out.put("inactivity", !context.enabled(Module.INACTIVITY) ? null : inactivity.covering(memberId, day).map(p -> {
            var period = new LinkedHashMap<String, Object>(); period.put("from", p.from()); period.put("to", p.to()); return period;
        }).orElse(null));
        return out;
    }
    private static Map<String, Object> counter(TrainingBookingService.Counter c) {
        var out = new LinkedHashMap<String, Object>(); out.put("used", c.used()); out.put("limit", c.limit()); out.put("remaining", c.remaining());
        out.put("resetsAt", c.weekEnd()); return out;
    }

    /** Another member's booking is 404, never 403; family-group members see each other's bookings (as in S08). */
    public TrainingBooking visible(String id, String memberId, boolean staff) {
        var b = bookings.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (staff) { return b; }
        if (memberId == null) { throw new ApiException(ErrorCode.NOT_FOUND); }
        boolean mine = census.reachableMembers(memberId).contains(b.memberId())
                || census.reachableDogs(memberId).stream().anyMatch(e -> e.getKey().id().equals(b.dogId()));
        if (!mine) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return b;
    }
    public List<Map<String, Object>> mine(String memberId, LocalDate from, LocalDate to, TrainingBookingState state) {
        if (from != null && to != null && to.isBefore(from)) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "to")); }
        var zone = context.zone();
        var dogs = census.reachableDogs(memberId).stream().map(e -> e.getKey().id()).toList();
        return bookings.visible(census.reachableMembers(memberId), dogs, state, from == null ? null : from.atStartOfDay(zone).toInstant(),
                to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant()).stream().map(this::view).toList();
    }

    /** S09 §6 `TrainingBooking` wire form. */
    public Map<String, Object> view(TrainingBooking b) {
        var zone = context.zone(); int threshold = context.cancelThreshold();
        var dog = census.dog(b.dogId()).orElse(null); var rings = service.ringNames();
        var out = new LinkedHashMap<String, Object>();
        out.put("id", b.id()); out.put("dogId", b.dogId()); out.put("dogName", dog == null ? "" : dog.name()); out.put("memberId", b.memberId());
        out.put("ringId", b.ringId()); out.put("ringName", Objects.toString(rings.get(b.ringId()), "")); out.put("slotId", b.slotId());
        out.put("startsAt", b.startsAt()); out.put("endsAt", b.endsAt());
        out.put("startsAtLocal", TrainingSlotService.hhmm(b.startsAt(), zone)); out.put("endsAtLocal", TrainingSlotService.hhmm(b.endsAt(), zone));
        out.put("date", b.startsAt().atZone(zone).toLocalDate()); out.put("state", b.state()); out.put("origin", b.origin());
        out.put("cancellableUntil", TrainingRules.cancellableUntil(b.startsAt(), threshold)); out.put("createdAt", b.createdAt());
        out.put("counter", counter(service.counter(b.dogId(), b.memberId(), b.startsAt())));
        out.put("impersonation", b.impersonatedByAccountId() == null ? null : Map.of("actorName", identities.displayName(b.impersonatedByAccountId())));
        out.put("cancelledAt", b.cancelledAt()); out.put("cancelledBy", b.cancelledBy()); out.put("cancelReason", b.cancelReason());
        return out;
    }
    public Map<String, Object> view(TrainingBookingService.Booked booked) {
        var out = view(booked.booking()); out.put("counter", counter(booked.counter())); return out;
    }

    @Override public Set<String> keys() { return Set.of("training-bookings"); }
    @Override public ListDataset dataset(String key) {
        String zone = context.zone().getId();
        var filters = new HashMap<String, ListDefinition.Field>(); filters.put("id", new ListDefinition.Field("_id", ListDefinition.Type.TEXT));
        for (String f : FILTERS) { filters.put(f, new ListDefinition.Field(f, f.equals("date") ? ListDefinition.Type.DATE : ListDefinition.Type.TEXT)); }
        var definition = new ListDefinition(key, filters, Map.of("startsAt", "startsAt"), List.of(), COLUMNS, COLUMNS.subList(0, 6),
                List.of("startsAt,desc"), Set.copyOf(FIELDS));
        var stages = new ArrayList<Document>();
        stages.add(new Document("$set", new Document("date", new Document("$dateToString", new Document("date", "$startsAt").append("format", "%Y-%m-%d").append("timezone", zone)))
                .append("startsAtLocal", new Document("$dateToString", new Document("date", "$startsAt").append("format", "%H:%M").append("timezone", zone)))));
        stages.add(lookup("members", "memberId", "memberRows")); stages.add(lookup("dogs", "dogId", "dogRows")); stages.add(lookup("rings", "ringId", "ringRows"));
        stages.add(new Document("$set", new Document("person", new Document("$arrayElemAt", List.of("$memberRows", 0)))
                .append("dogName", new Document("$ifNull", List.of(new Document("$arrayElemAt", List.of("$dogRows.name", 0)), "")))
                .append("ringName", new Document("$ifNull", List.of(new Document("$arrayElemAt", List.of("$ringRows.name", 0)), "")))));
        stages.add(new Document("$set", new Document("memberName", new Document("$trim", new Document("input", new Document("$concat", List.of(
                new Document("$ifNull", List.of("$person.firstName", "")), " ", new Document("$ifNull", List.of("$person.lastName1", "")))))))));
        var output = new LinkedHashMap<String, Object>(); FIELDS.forEach(f -> output.put(f, 1)); output.put("id", "$_id"); output.put("_id", 0);
        return new ListDataset(definition, "training_bookings", stages, output, Set.of(), (field, value) -> Objects.toString(value, ""));
    }
    private static Document lookup(String collection, String field, String alias) {
        return new Document("$lookup", new Document("from", collection).append("let", new Document("ref", "$" + field).append("club", "$clubId"))
                .append("pipeline", List.of(new Document("$match", new Document("$expr", new Document("$and", List.of(
                        new Document("$eq", List.of("$clubId", "$$club")), new Document("$eq", List.of("$_id", "$$ref"))))))))
                .append("as", alias));
    }
    public ListPage<Map<String, Object>> list(ListEngine engine, MultiValueMap<String, String> params) { return engine.list("training-bookings", params); }
}
