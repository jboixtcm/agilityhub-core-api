package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.domain.*;
import com.agilityhub.core.clubs.bookings.persistence.*;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.lists.ListEngine;
import java.time.Instant;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** E5-T08 (review E5-T02 #11): `GET /bookings` reads a page with one `$in` per collection, never one read per row. */
class BookingQueryServiceTest {
    @Test void T_08_47_aPageOfFiftyRowsReadsBookingsDogsAndMembersOnceEach() {
        var repository = mock(BookingRepository.class); var census = mock(BookingMemberAccess.class); var views = mock(BookingViews.class); var engine = mock(ListEngine.class);
        var rows = IntStream.range(0, 50).<Map<String, Object>>mapToObj(i -> Map.of("id", "b" + i)).toList();
        when(engine.list(eq("bookings"), any())).thenReturn(new ListPage<>(rows, 0, 50, 120, 3, List.of()));
        var stored = new ArrayList<>(IntStream.range(0, 50).mapToObj(i -> booking("b" + i, "d" + i % 5, "m" + i % 5)).toList());
        Collections.reverse(stored); // `$in` gives no order: the page keeps the engine's order
        when(repository.byIds(any())).thenReturn(stored);
        when(census.dogs(any())).thenReturn(IntStream.range(0, 5).mapToObj(i -> new BookingMemberAccess.Dog("d" + i, "Dog " + i, "MALE", "m" + i, "level", "ACTIVE")).toList());
        when(census.members(any())).thenReturn(IntStream.range(0, 5).mapToObj(i -> new BookingMemberAccess.Member("m" + i, "a" + i, "Example", "Example " + i,
                "ACTIVE", null, false, null, null, null, "ca", null, List.of())).toList());
        when(views.listItem(any(), any(), any())).thenCallRealMethod();

        var page = new BookingQueryService(repository, census, views, null, null).list(engine, new LinkedMultiValueMap<>());

        assertThat(page.items()).hasSize(50); assertThat(page.totalItems()).isEqualTo(120);
        assertThat(page.items()).extracting(i -> i.get("id")).containsExactlyElementsOf(rows.stream().map(r -> r.get("id")).toList());
        assertThat(page.items().get(7)).containsEntry("dogName", "Dog 2").containsEntry("memberName", "Example 2");
        verify(repository, times(1)).byIds(argThat(ids -> ids.size() == 50)); verify(repository, never()).findById(any());
        verify(census, times(1)).dogs(argThat(ids -> ids.size() == 5)); verify(census, times(1)).members(argThat(ids -> ids.size() == 5));
        verify(census, never()).member(any()); verify(census, never()).dog(any());
    }

    private static Booking booking(String id, String dogId, String memberId) {
        var starts = Instant.parse("2026-10-07T16:50:00Z");
        return new Booking(id, "club-fixture", "class", dogId, memberId, BookingState.ACTIVE, BookingOrigin.APP, starts, null, starts, starts.plusSeconds(3600),
                "2026-10-04", null, null, null, null, null, null, null, null, null, null, null, null, null, 0L, starts, null, starts, null);
    }
}
