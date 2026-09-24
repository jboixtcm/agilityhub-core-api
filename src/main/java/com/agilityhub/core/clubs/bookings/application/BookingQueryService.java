package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.BookingState;
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
    static final List<String> FIELDS = List.of("id", "state", "origin", "classSessionId", "classStartsAt", "bookingWeekKey", "dogId", "memberId", "bookedAt", "late");
    private final BookingRepository bookings; private final BookingMemberAccess census; private final BookingViews views;
    private final BookingContext context; private final BookingCalendarTokens tokens;
    public BookingQueryService(BookingRepository bookings, BookingMemberAccess census, BookingViews views, BookingContext context, BookingCalendarTokens tokens) {
        this.bookings = bookings; this.census = census; this.views = views; this.context = context; this.tokens = tokens;
    }
    public Booking visible(String id, String actorMemberId, boolean staff) {
        var booking = bookings.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
        if (!staff && (actorMemberId == null || !census.reachableMembers(actorMemberId).contains(booking.memberId()))) { throw new ApiException(ErrorCode.NOT_FOUND); }
        return booking;
    }
    public Map<String, Object> detail(String id, String actorMemberId, boolean staff) { return views.booking(visible(id, actorMemberId, staff), staff, null); }

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
    public List<Map<String, Object>> forClass(String classSessionId) { return items(bookings.forClass(classSessionId)); }
    /** S09 R-09-06 step 3: the dog already has a live class booking overlapping `[from, to)` (DOG_ALREADY_BOOKED). */
    public boolean dogInClass(String dogId, java.time.Instant from, java.time.Instant to) { return !bookings.liveOverlapping(dogId, from, to).isEmpty(); }

    @Override public Set<String> keys() { return Set.of("bookings"); }
    @Override public ListDataset dataset(String key) {
        var filters = new HashMap<String, ListDefinition.Field>(); filters.put("id", new ListDefinition.Field("_id", ListDefinition.Type.TEXT));
        for (String f : FILTERS) { filters.put(f, new ListDefinition.Field(f, f.equals("classStartsAt") ? ListDefinition.Type.INSTANT : ListDefinition.Type.TEXT)); }
        var columns = List.of("classStartsAt", "dogName", "memberName", "state", "origin", "bookedAt", "bookingWeekKey", "late");
        var definition = new ListDefinition(key, filters, Map.of("classStartsAt", "classStartsAt", "bookedAt", "bookedAt"), List.of(), columns,
                List.of("classStartsAt", "dogName", "memberName", "state", "origin"), List.of("classStartsAt,asc"), Set.copyOf(FIELDS));
        var output = new LinkedHashMap<String, Object>(); FIELDS.forEach(f -> output.put(f, 1)); output.put("id", "$_id");
        return new ListDataset(definition, "bookings", List.<Document>of(), output, Set.of("late"), (field, value) -> Objects.toString(value, ""));
    }
    public ListPage<Map<String, Object>> list(ListEngine engine, MultiValueMap<String, String> params) {
        var page = engine.list("bookings", params);
        var ids = page.items().stream().map(row -> row.get("id").toString()).toList();
        var byId = new HashMap<String, Booking>(); ids.forEach(id -> bookings.findById(id).ifPresent(b -> byId.put(id, b)));
        return new ListPage<>(items(ids.stream().map(byId::get).filter(Objects::nonNull).toList()), page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters());
    }
    private List<Map<String, Object>> items(List<Booking> list) {
        var dogs = new HashMap<String, BookingMemberAccess.Dog>(); census.dogs(list.stream().map(Booking::dogId).distinct().toList()).forEach(d -> dogs.put(d.id(), d));
        var members = new HashMap<String, String>();
        list.stream().map(Booking::memberId).distinct().forEach(m -> census.member(m).ifPresent(member -> members.put(m, member.displayName())));
        return list.stream().map(b -> views.listItem(b, dogs, members)).toList();
    }
}
