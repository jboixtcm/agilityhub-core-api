package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.*;
import java.time.LocalDate;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * S08 reads: detail 07 (member own or family group, instructor, admin — another member's booking is 404),
 * `/me/bookings`, the universal list `GET /bookings` (ADMIN/INSTRUCTOR) and a class's bookings (21/D4/D12).
 */
@Service
public class BookingQueryService implements ListProvider {
    static final List<String> FILTERS = List.of("state", "dogId", "memberId", "classSessionId", "bookingWeekKey", "origin", "classStartsAt");
    /** The stored keys the engine's page projects; the rows are rebuilt whole from the page's bookings anyway. */
    static final List<String> FIELDS = List.of("id", "state", "origin", "classSessionId", "classStartsAt", "bookingWeekKey", "dogId", "memberId", "bookedAt", "late");
    /**
     * The keys `fields` accepts, the list's `x-fields`: every key of `BookingListItem`, the names included (E5-T22), and D10's class
     * description and ring (E5-T29).
     */
    public static final Set<String> ITEM_FIELDS = Set.of("id", "state", "origin", "classSessionId", "classStartsAt", "bookingWeekKey", "dogId", "dogName", "memberId",
            "memberName", "bookedAt", "late", "classDescription", "ringId", "ringName", "ringColor");
    private final BookingRepository bookings; private final BookingMemberAccess census; private final BookingViews views;
    private final BookingContext context; private final BookingCalendarTokens tokens; private final AttendanceRepository attendances;
    private final com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess catalogs;
    public BookingQueryService(BookingRepository bookings, BookingMemberAccess census, BookingViews views, BookingContext context, BookingCalendarTokens tokens,
            AttendanceRepository attendances, com.agilityhub.core.clubs.catalogs.application.PlanningCatalogAccess catalogs) {
        this.bookings = bookings; this.census = census; this.views = views; this.context = context; this.tokens = tokens; this.attendances = attendances;
        this.catalogs = catalogs;
    }
    public Booking visible(String id, String actorMemberId, boolean staff) {
        var booking = bookings.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!staff && (actorMemberId == null || !census.reachableMembers(actorMemberId).contains(booking.memberId()))) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return booking;
    }
    public Map<String, Object> detail(String id, String actorMemberId, boolean staff) { return views.booking(visible(id, actorMemberId, staff), staff, null); }
    /**
     * S01 R-01-07 (E5-T27, ruling E41): whether the booking is the member's own or their family group's, so that an account with
     * MEMBER and a staff role acts on it as a member. `NOT_FOUND` for an unknown booking.
     */
    public boolean reachable(String id, String actorMemberId) {
        var booking = bookings.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        return actorMemberId != null && census.reachableMembers(actorMemberId).contains(booking.memberId());
    }

    /** Own and family-group bookings; `dogId` must be accessible, `from`/`to` are club-local dates (inclusive). */
    public List<Map<String, Object>> mine(String actorMemberId, String dogId, BookingState state, LocalDate from, LocalDate to) {
        var dogs = census.accessibleDogs(actorMemberId).stream().map(BookingMemberAccess.Dog::id).toList();
        if (dogId != null && !dogs.contains(dogId)) { throw new ApiException(ErrorCode.DOG_NOT_ACCESSIBLE); }
        if (from != null && to != null && to.isBefore(from)) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "to")); }
        var zone = context.zone();
        return bookings.forDogs(dogId == null ? dogs : List.of(dogId), state == null ? List.of() : List.of(state),
                from == null ? null : from.atStartOfDay(zone).toInstant(), to == null ? null : to.plusDays(1).atStartOfDay(zone).toInstant())
                .stream().map(b -> views.booking(b, false, null)).toList();
    }
    /** `GET /bookings/{id}/calendar.ics`: the signed token replaces the JWT (404 on any mismatch or after `classEndsAt`). */
    public String calendar(String id, String token) {
        if (token == null || token.isBlank()) { throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("field", "token")); }
        var booking = bookings.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        tokens.require(id, token);
        var session = views.labelsOf(booking);
        return com.agilityhub.core.clubs.bookings.domain.BookingCalendar.ics(views.event(booking, session));
    }
    /**
     * The registrants of a class (21, D4, D12, the instructor's drawer; E5-T29): each row with S08 §6 `displayState`, derived as
     * `GET /bookings/{id}` does (a past ACTIVE booking is DONE, one marked NO_SHOW is NO_SHOW), and the dog's level code, null
     * with `levels.enabled = false` or without a level (R-10-16). One read per collection for the whole class.
     */
    public List<Map<String, Object>> forClass(String classSessionId) {
        var list = bookings.forClass(classSessionId); var now = context.now();
        var dogs = dogs(list); var members = members(list);
        var marks = attendances.byBookings(list.stream().map(Booking::id).toList());
        var levels = levelCodes();
        return list.stream().map(b -> {
            var row = views.listItem(b, dogs, members); var mark = marks.get(b.id()); var dog = dogs.get(b.dogId());
            row.put("displayState", BookingDisplay.of(b.state(), b.classEndsAt(), now, mark != null && mark.state() == AttendanceState.NO_SHOW));
            row.put("levelCode", dog == null || dog.levelId() == null ? null : levels.get(dog.levelId()));
            return row;
        }).toList();
    }
    /** Level id → code; empty with `levels.enabled = false` (R-10-16: no level chip anywhere), as the attendance sheet reads it. */
    private Map<String, String> levelCodes() {
        if (!context.flag("levels.enabled")) { return Map.of(); }
        var codes = new HashMap<String, String>(); catalogs.levelRefs().forEach((id, level) -> codes.put(id, level.code()));
        return codes;
    }
    /** S09 R-09-06 step 3: the dog already has a live class booking overlapping `[from, to)` (DOG_ALREADY_BOOKED). */
    public boolean dogInClass(String dogId, java.time.Instant from, java.time.Instant to) { return !bookings.liveOverlapping(dogId, from, to).isEmpty(); }

    @Override public Set<String> keys() { return Set.of("bookings"); }
    @Override public ListDataset dataset(String key) {
        var filters = new HashMap<String, ListDefinition.Field>(); filters.put("id", new ListDefinition.Field("_id", ListDefinition.Type.TEXT));
        for (String f : FILTERS) { filters.put(f, new ListDefinition.Field(f, f.equals("classStartsAt") ? ListDefinition.Type.INSTANT : ListDefinition.Type.TEXT)); }
        var columns = List.of("classStartsAt", "dogName", "memberName", "state", "origin", "bookedAt", "bookingWeekKey", "late");
        var definition = new ListDefinition(key, filters, Map.of("classStartsAt", "classStartsAt", "bookedAt", "bookedAt"), List.of(), columns,
                List.of("classStartsAt", "dogName", "memberName", "state", "origin"), List.of("classStartsAt,asc"), ITEM_FIELDS);
        var output = new LinkedHashMap<String, Object>(); FIELDS.forEach(f -> output.put(f, 1)); output.put("id", "$_id");
        return new ListDataset(definition, "bookings", List.<Document>of(), output, Set.of("late"), this::label);
    }
    /**
     * `GET /bookings/filter-values` labels (E5-T29, CONVENCIONS_API §4): the dog's name, the member's display name and the class's
     * start and description, read for the (at most 50) values listed; any other field, or a record that is gone, is its value.
     */
    private String label(String field, Object value) {
        String id = Objects.toString(value, "");
        return switch (field) {
            case "dogId" -> census.dog(id).map(BookingMemberAccess.Dog::name).orElse(id);
            case "memberId" -> census.member(id).map(BookingMemberAccess.Member::displayName).orElse(id);
            case "classSessionId" -> views.classLabel(id);
            default -> id;
        };
    }
    /** CONVENCIONS_API §4: the values of `field` (an `x-filterable` field) with their counts over the whole set `q` and `filter` select. */
    public com.agilityhub.core.shared.application.contract.ApiContracts.FilterValues filterValues(ListEngine engine, String field, MultiValueMap<String, String> params) {
        return engine.facets("bookings", field, params);
    }
    public ListPage<Map<String, Object>> list(ListEngine engine, MultiValueMap<String, String> params) {
        var page = engine.list("bookings", params);
        var ids = page.items().stream().map(row -> row.get("id").toString()).toList();
        // One `$in` per collection for the page (bookings, dogs, members), never one read per row (E5-T08).
        var byId = new HashMap<String, Booking>(); bookings.byIds(ids).forEach(b -> byId.put(b.id(), b));
        return new ListPage<>(items(ids.stream().map(byId::get).filter(Objects::nonNull).toList()), page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters());
    }
    /** D10 «Classes» rows (E5-T29): each with its class's display description (the reader's language), ring and ring colour. */
    private List<Map<String, Object>> items(List<Booking> list) {
        var dogs = dogs(list); var members = members(list);
        var labels = views.classLabels(list.stream().map(Booking::classSessionId).distinct().toList());
        return list.stream().map(b -> {
            var row = views.listItem(b, dogs, members); var label = labels.get(b.classSessionId());
            row.put("classDescription", label == null ? "" : label.description()); row.put("ringId", label == null ? null : label.ringId());
            row.put("ringName", label == null ? null : label.ringName()); row.put("ringColor", label == null ? null : label.ringColor());
            return row;
        }).toList();
    }
    private Map<String, BookingMemberAccess.Dog> dogs(List<Booking> list) {
        var dogs = new HashMap<String, BookingMemberAccess.Dog>(); census.dogs(list.stream().map(Booking::dogId).distinct().toList()).forEach(d -> dogs.put(d.id(), d));
        return dogs;
    }
    private Map<String, String> members(List<Booking> list) {
        var members = new HashMap<String, String>();
        census.members(list.stream().map(Booking::memberId).filter(Objects::nonNull).distinct().toList()).forEach(m -> members.put(m.id(), m.displayName()));
        return members;
    }
}
