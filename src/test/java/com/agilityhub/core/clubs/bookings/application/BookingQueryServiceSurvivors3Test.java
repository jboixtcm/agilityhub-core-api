package com.agilityhub.core.clubs.bookings.application;

import com.agilityhub.core.clubs.bookings.persistence.BookingRepository;
import com.agilityhub.core.clubs.census.application.BookingMemberAccess;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.persistence.MongoListRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 session 10 PIT survivors of {@link BookingQueryService} (S08 §6 `GET /bookings`, T-08-47): the universal list
 * engine finds the booking dataset under its key `bookings` (ListEngine registers each provider by its keys; an unknown
 * key is INVALID_FILTER).
 */
class BookingQueryServiceSurvivors3Test {
    @Test void T_08_47_theListEngineServesTheBookingsUnderTheirKey() {
        var service = new BookingQueryService(mock(BookingRepository.class), mock(BookingMemberAccess.class), mock(BookingViews.class),
                mock(BookingContext.class), null, null, null);
        var engine = new ListEngine(List.of(service), mock(MongoListRepository.class));

        assertThat(service.keys()).containsExactly("bookings");
        assertThat(engine.dataset("bookings").collection()).isEqualTo("bookings");
    }
}
