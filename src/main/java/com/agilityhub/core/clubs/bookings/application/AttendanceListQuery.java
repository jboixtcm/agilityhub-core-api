package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.Attendance;
import com.agilityhub.core.clubs.bookings.persistence.AttendanceRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.*;
import java.util.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import static com.agilityhub.core.clubs.bookings.application.BookingViews.map;

/**
 * S10 §6 `GET /attendances` (ADMIN, INSTRUCTOR): the universal list (CONVENCIONS_API §4) over the stored attendances
 * with the allowlist of {@link AttendanceContractAccess#ATTENDANCES} (`x-filterable: dogId, memberId, classSessionId,
 * classDate, state`; `x-sortable: classStartsAt, classDate`). No export: S14 R-14-12 declares no `x-exportable` for it.
 * A booking without an `Attendance` document (PENDING) is not a row. The page's dog and member names are read with one
 * `$in` each.
 */
@Service
public class AttendanceListQuery implements ListProvider {
    static final String KEY = "attendances";
    private final AttendanceRepository attendances; private final BookingMemberAccess census;
    public AttendanceListQuery(AttendanceRepository attendances, BookingMemberAccess census) { this.attendances = attendances; this.census = census; }

    @Override public Set<String> keys() { return Set.of(KEY); }
    @Override public ListDataset dataset(String key) {
        var output = new LinkedHashMap<String, Object>(); output.put("id", "$_id");
        for (String field : List.of("bookingId", "classSessionId", "classDate", "classStartsAt", "dogId", "memberId", "state", "markedAt")) { output.put(field, 1); }
        return new ListDataset(AttendanceContractAccess.ATTENDANCES, KEY, List.<Document>of(), output, Set.of("markedAt"), (field, value) -> Objects.toString(value, ""));
    }
    public ListPage<Map<String, Object>> list(ListEngine engine, MultiValueMap<String, String> params) {
        var page = engine.list(KEY, params);
        var ids = page.items().stream().map(row -> row.get("id").toString()).toList();
        var byId = new HashMap<String, Attendance>(); attendances.byIds(ids).forEach(a -> byId.put(a.id(), a));
        var rows = ids.stream().map(byId::get).filter(Objects::nonNull).toList();
        var dogs = new HashMap<String, String>(); census.dogs(rows.stream().map(Attendance::dogId).distinct().toList()).forEach(d -> dogs.put(d.id(), d.name()));
        var members = new HashMap<String, String>();
        census.members(rows.stream().map(Attendance::memberId).distinct().toList()).forEach(m -> members.put(m.id(), m.displayName()));
        var items = rows.stream().map(a -> map("id", a.id(), "bookingId", a.bookingId(), "classSessionId", a.classSessionId(), "classDate", a.classDate(),
                "classStartsAt", a.classStartsAt(), "dogId", a.dogId(), "dogName", dogs.get(a.dogId()), "memberId", a.memberId(), "memberName", members.get(a.memberId()),
                "state", a.state(), "markedAt", a.markedAt(), "markedByName", a.markedBy() == null ? null : a.markedBy().displayName())).toList();
        return new ListPage<>(items, page.page(), page.size(), page.totalItems(), page.totalPages(), page.appliedFilters());
    }
}
