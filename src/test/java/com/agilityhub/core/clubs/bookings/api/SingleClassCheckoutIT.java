package com.agilityhub.core.clubs.bookings.api;

import com.agilityhub.core.clubs.bookings.application.PaymentTimeoutsJob;
import com.agilityhub.core.clubs.bookings.application.ports.SingleClassChargePort;
import com.agilityhub.core.payments.application.FakeCheckoutGateway;
import com.agilityhub.core.platform.application.jobs.JobRunner;
import com.agilityhub.core.platform.persistence.jobs.JobRun;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.IdempotencyRepository;
import com.mongodb.MongoException;
import java.time.Duration;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

/**
 * S08 R-08-18 PAY_TO_BOOK and the provider: the checkout opens after the booking transaction commits, so a
 * retried transaction never opens a second provider checkout and no seat lock is held during the provider call;
 * a failed provider call releases the seat at once.
 */
class SingleClassCheckoutIT extends BookingFixtures {
    @MockitoSpyBean SingleClassChargePort charges;
    @MockitoSpyBean FakeCheckoutGateway gateway;
    @MockitoSpyBean IdempotencyRepository records;
    @Autowired JobRunner runner;
    @Autowired PaymentTimeoutsJob timeouts;

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
        // The provider completes it: ACTIVE, and the checkout link is gone from the booking.
        gateway.complete(sessionId); dispatch();
        assertThat(booking(bookingId).getString("state")).isEqualTo("ACTIVE");
        assertThat(booking(bookingId).get("charge", Document.class).get("checkoutUrl")).isNull();
        assertThat(call(GET, "/bookings/" + bookingId, null, as("laura"), 200).path("checkoutUrl").isNull()).isTrue();
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
        // E30: the member already saw the error, so the cancellation carries `checkoutFailed` and sends no N-40.
        assertThat(eventsOf("BookingCancelled")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class))
                .containsEntry("reason", "PAYMENT_TIMEOUT").containsEntry("checkoutFailed", true));
        assertThat(count("notifications", Criteria.where("code").is("N-40"))).isZero();
        assertThat(book(as("joan"), "wed", "s08-d-toby").path("state").asText()).as("the seat is free again").isEqualTo("ACTIVE");
    }

    @Test void T_08_24_whenTheFailedPaymentConsumerCancelsFirstTheProviderFailureIsRethrownAndTheKeyReleased() throws Exception {
        payToBook();
        // While the provider call is failing, the provider expires the session and the UpfrontPaymentFailed consumer cancels first.
        doAnswer(invocation -> {
            invocation.callRealMethod();
            gateway.expire(invocation.<com.agilityhub.core.payments.application.PaymentProvider.Request>getArgument(0).sessionId());
            dispatch();
            throw new IllegalStateException("provider timeout after the session expired (injected)");
        }).when(gateway).createCheckoutSession(any());
        String key = UUID.randomUUID().toString(), holdId = hold(as("laura"), "wed", "s08-d-duna", 201).path("id").asText();
        // The original failure (500), not the 422 BOOKING_NOT_CANCELLABLE of a second cancellation.
        var error = call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 500, key);
        assertThat(code(error)).isEqualTo("INTERNAL_ERROR");
        var failed = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("dogId").is("s08-d-duna")), Document.class, "bookings");
        assertThat(failed).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "PAYMENT_TIMEOUT");
        assertThat(events("BookingCancelled")).isEqualTo(1);
        assertThat(eventsOf("BookingCancelled")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).doesNotContainKey("checkoutFailed"));
        // No 422 was stored for the key: it was released, and the same key finds the hold consumed.
        assertThat(count("idempotency_records", Criteria.where("responseStatus").is(422))).isZero();
        assertThat(code(call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 409, key))).isEqualTo("SEAT_HOLD_EXPIRED");
    }

    @Test void T_08_24_aProviderFailureWithABusinessStatusStillReleasesTheKey() throws Exception {
        payToBook();
        doThrow(new ApiException(ErrorCode.INVALID_STATE)).when(gateway).createCheckoutSession(any());
        String key = UUID.randomUUID().toString(), holdId = hold(as("laura"), "wed", "s08-d-duna", 201).path("id").asText();
        assertThat(code(call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 409, key))).isEqualTo("INVALID_STATE");
        // A 409 from the provider call is not replayed: the key is gone and the retry reaches the (consumed) hold.
        assertThat(count("idempotency_records", new Criteria())).isZero();
        assertThat(code(call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 409, key))).isEqualTo("SEAT_HOLD_EXPIRED");
    }

    @Test void T_08_24_payToBookNeedsTheProviderEnabledForTheClub() throws Exception {
        payToBook();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().unset("paymentProviders.STRIPE"), "clubs");
        configs.invalidate(CLUB);
        String holdId = hold(as("laura"), "wed", "s08-d-duna", 201).path("id").asText();
        var error = call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 422, UUID.randomUUID().toString());
        assertThat(code(error)).isEqualTo("PAYMENT_PROVIDER_NOT_ENABLED");
        assertThat(count("bookings", new Criteria())).isZero(); assertThat(count("upfront_payments", new Criteria())).isZero();
        assertThat(count("checkout_sessions", new Criteria())).isZero();
        verify(gateway, never()).createCheckoutSession(any());
    }

    @Test void T_08_24_theCheckoutUrlIsKeptOnTheBookingWhenTheResponseIsNotStored() throws Exception {
        payToBook();
        // The checkout opens, then storing the 201 fails (retries exhausted): the key is released and the 201 is lost.
        doThrow(new ApiException(ErrorCode.STALE_VERSION)).doCallRealMethod().when(records).complete(any(), anyInt(), any(), any());
        String key = UUID.randomUUID().toString(), holdId = hold(as("laura"), "wed", "s08-d-duna", 201).path("id").asText();
        assertThat(code(call(POST, "/bookings", Map.of("seatHoldId", holdId), as("laura"), 409, key))).isEqualTo("STALE_VERSION");
        var stored = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("dogId").is("s08-d-duna")), Document.class, "bookings");
        String bookingId = stored.getString("_id"), url = "https://checkout.test/" + checkoutSession(bookingId);
        assertThat(stored).containsEntry("state", "PAYMENT_PENDING");
        assertThat(stored.get("charge", Document.class)).containsEntry("checkoutUrl", url);
        // S08 §6: the booking resource carries it, so the member finds the link again.
        var read = call(GET, "/bookings/" + bookingId, null, as("laura"), 200);
        assertThat(read.path("state").asText()).isEqualTo("PAYMENT_PENDING");
        assertThat(read.path("checkoutUrl").asText()).isEqualTo(url);
        verify(gateway, times(1)).createCheckoutSession(any());
    }

    @Test void T_15_25_R_08_18_aTimedOutBookingExpiresItsCheckoutSoALateSuccessNeverSettlesItAndN40IsSent() throws Exception {
        payToBook();
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "job_runs"); mongo.remove(new Query(), "job_locks");
        var booking = book(as("laura"), "wed", "s08-d-duna");
        String bookingId = booking.path("id").asText(), sessionId = checkoutSession(bookingId);
        assertThat(booking.path("state").asText()).isEqualTo("PAYMENT_PENDING");
        dispatch();
        // P7, `bookings.paymentPendingMinutes` (30) later: the booking is cancelled, and its session and line are closed with it.
        clock.setInstant(NOW.plus(Duration.ofMinutes(31)));
        var run = runner.scheduled(CLUB, true, timeouts, clock.instant()).orElseThrow();
        assertThat(run.items()).extracting(JobRun.Item::entityId, JobRun.Item::action).containsExactly(tuple(bookingId, "CANCEL"));
        assertThat(booking(bookingId)).containsEntry("state", "CANCELLED").containsEntry("cancelReason", "PAYMENT_TIMEOUT");
        assertThat(booking(bookingId).get("charge", Document.class).get("checkoutUrl")).isNull();
        assertThat(mongo.findById(sessionId, Document.class, "checkout_sessions")).containsEntry("status", "EXPIRED");
        assertThat(line(bookingId)).containsEntry("status", "CANCELLED");
        // A late provider success finds the session EXPIRED: nothing is settled, the booking stays CANCELLED.
        gateway.complete(sessionId); dispatch();
        assertThat(booking(bookingId).getString("state")).isEqualTo("CANCELLED");
        assertThat(line(bookingId)).containsEntry("status", "CANCELLED");
        assertThat(eventsOf("UpfrontPaymentSucceeded")).isEmpty();
        assertThat(events("BookingCancelled")).isEqualTo(1);
        // E30 changes nothing on the timeout path: N-40 is sent.
        assertThat(eventsOf("BookingCancelled")).singleElement().satisfies(e -> assertThat(e.get("payload", Document.class)).doesNotContainKey("checkoutFailed"));
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-40")), Document.class, "notifications"))
                .extracting(n -> n.getString("accountId")).containsOnly("s08-laura").isNotEmpty();
    }
}
