package com.agilityhub.core.clubs.bookings.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BookingRepository} (S08 §3): a missing booking is 404, the checkout session finds its
 * booking, and the set-once writes answer from the modified count.
 */
class BookingRepositorySurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-05T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final BookingRepository bookings = new BookingRepository(mongo);

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open(CLUB); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void T_08_26_aMissingBookingIsNotFound() {
        assertThatThrownBy(() -> bookings.require("booking-b"))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.NOT_FOUND));
    }

    @Test void T_08_24_theCheckoutSessionFindsItsBookingInTheClub() {
        var booking = mock(Booking.class);
        when(mongo.findOne(any(Query.class), eq(Booking.class))).thenReturn(booking);

        assertThat(bookings.byCheckoutSession("session-1")).containsSame(booking);
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).findOne(query.capture(), eq(Booking.class));
        assertThat(query.getValue().getQueryObject()).containsEntry("clubId", CLUB).containsEntry("charge.checkoutSessionId", "session-1");
    }

    @Test void E11_T06_aReminderMarkIsTrueOnlyWhenItWasWritten() {
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(Booking.class))).thenReturn(modified(0), modified(1));

        assertThat(bookings.markReminderSent("booking-1", NOW, NOW)).isFalse();
        assertThat(bookings.markReminderSent("booking-1", NOW, NOW)).isTrue();
    }

    @Test void E11_T06_aChargeReferenceStampIsTrueOnlyWhenItWasWritten() {
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq("bookings"))).thenReturn(modified(0), modified(1));

        assertThat(bookings.stampChargeRef("booking-1", "charge-1")).isFalse();
        assertThat(bookings.stampChargeRef("booking-1", "charge-1")).isTrue();
    }

    @Test void E11_T06_aChargeReferenceClearIsTrueOnlyWhenItStillNamedTheCharge() {
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq("bookings"))).thenReturn(modified(0), modified(1));

        assertThat(bookings.clearChargeRef("booking-1", "charge-1")).isFalse();
        assertThat(bookings.clearChargeRef("booking-1", "charge-1")).isTrue();
        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo, times(2)).updateFirst(query.capture(), any(UpdateDefinition.class), eq("bookings"));
        assertThat(query.getValue().getQueryObject()).containsEntry("charge.chargeInvoiceLineRef", "charge-1");
    }

    static UpdateResult modified(long count) { return UpdateResult.acknowledged(count, count, null); }
}
