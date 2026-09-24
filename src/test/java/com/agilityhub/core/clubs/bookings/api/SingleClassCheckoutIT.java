package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.payments.application.FakeCheckoutGateway;
import com.mongodb.MongoException;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpMethod.POST;

/**
 * S08 R-08-18 PAY_TO_BOOK and the provider: the checkout opens after the booking transaction commits, so a
 * retried transaction never opens a second provider checkout and no seat lock is held during the provider call;
 * a failed provider call releases the seat at once.
 */
class SingleClassCheckoutIT extends BookingFixtures {
    @MockitoSpyBean SingleClassChargePort charges;
    @MockitoSpyBean FakeCheckoutGateway gateway;

    @Test void T_08_24_aRetriedConfirmationOpensOneProviderCheckoutAfterTheCommit() throws Exception {
        payToBook();
        // The first attempt writes the line and the session, then hits a write conflict: the transaction rolls back and retries.
        doAnswer(invocation -> { invocation.callRealMethod(); throw new MongoException(112, "WriteConflict (injected)"); })
                .doCallRealMethod().when(charges).prepare(any(), any(), any(), any(), any());
        String key = UUID.randomUUID().toString(), holdId = hold(as("laura"), "wed", "s08-d-duna", 201).path("id").asText();
        var booking = call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 201, key);
        String bookingId = booking.path("id").asText(), sessionId = checkoutSession(bookingId);
        verify(charges, times(2)).prepare(any(), any(), any(), any(), any());
        verify(gateway, times(1)).createCheckoutSession(any());
        assertThat(gateway.request(sessionId).metadata()).containsEntry("bookingId", bookingId);
        assertThat(booking.path("state").asText()).isEqualTo("PAYMENT_PENDING"); assertThat(booking.path("checkoutUrl").asText()).isEqualTo("https://checkout.test/" + sessionId);
        assertThat(count("bookings", new Criteria())).isEqualTo(1); assertThat(count("upfront_payments", new Criteria())).isEqualTo(1);
        assertThat(count("checkout_sessions", new Criteria())).isEqualTo(1); assertThat(line(bookingId)).containsEntry("checkoutSessionId", sessionId);
        // The stored 201 (written after the checkout opened) replays with the same checkoutUrl and calls nobody.
        var replay = call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 201, key);
        assertThat(replay.path("id").asText()).isEqualTo(bookingId); assertThat(replay.path("checkoutUrl").asText()).isEqualTo(booking.path("checkoutUrl").asText());
        verify(gateway, times(1)).createCheckoutSession(any());
        // The provider completes it: ACTIVE.
        gateway.complete(sessionId); dispatch();
        assertThat(booking(bookingId).getString("state")).isEqualTo("ACTIVE");
    }

    @Test void T_08_24_aFailedProviderCallCancelsTheBookingAndReleasesTheSeatAtOnce() throws Exception {
        payToBook();
        doThrow(new IllegalStateException("provider unavailable (injected)")).when(gateway).createCheckoutSession(any());
        String key = UUID.randomUUID().toString(), holdId = hold(as("laura"), "wed", "s08-d-duna", 201).path("id").asText();
        call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 500, key);
        var failed = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("dogId").is("s08-d-duna")), Document.class, "bookings");
        assertThat(failed).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "PAYMENT_TIMEOUT").containsEntry("late", false);
        assertThat(line(failed.getString("_id"))).containsEntry("status", "CANCELLED");
        assertThat(mongo.findById(checkoutSession(failed.getString("_id")), Document.class, "checkout_sessions")).containsEntry("status", "EXPIRED");
        assertThat(session("wed").get("counters", Document.class)).containsEntry("booked", 0); assertThat(events("SeatReleased")).isEqualTo(1);
        // The key was released (a 5xx is not a business outcome): the same key runs again and finds the hold consumed.
        assertThat(code(call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 409, key))).isEqualTo("SEAT_HOLD_EXPIRED");
        // UpfrontPaymentFailed reaches a booking that is already CANCELLED: nothing more happens.
        dispatch();
        assertThat(eventsOf("UpfrontPaymentFailed")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).containsEntry("bookingId", failed.getString("_id")));
        assertThat(events("BookingCancelled")).isEqualTo(1);
        assertThat(book(as("joan"), "wed", "s08-d-toby").path("state").asText()).as("the seat is free again").isEqualTo("ACTIVE");
    }
}
