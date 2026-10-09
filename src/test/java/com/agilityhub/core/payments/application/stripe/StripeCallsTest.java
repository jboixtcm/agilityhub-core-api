package com.agilityhub.core.payments.application.stripe;

import com.agilityhub.core.shared.domain.*;
import com.stripe.exception.ApiException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class StripeCallsTest {
    @Test void T_12_15_rateLimitRetriesTwiceWithBoundedExponentialBackoff() {
        var waits = new ArrayList<Long>(); var calls = new StripeCalls(waits::add); var count = new AtomicInteger();
        assertThat(calls.call(() -> { if (count.incrementAndGet() < 3) { throw failure(429); } return "ok"; })).isEqualTo("ok");
        assertThat(waits).containsExactly(100L, 200L);
        assertThatThrownBy(() -> calls.call(() -> { throw failure(429); }))
                .isInstanceOfSatisfying(com.agilityhub.core.shared.domain.ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.RATE_LIMITED));
    }
    @Test void T_12_32_providerErrorsNeverExposeMessagesAndAreNotRetried() {
        for (int status : List.of(400, 401, 403, 500)) {
            var count = new AtomicInteger(); var calls = new StripeCalls(millis -> fail("Unexpected retry"));
            assertThatThrownBy(() -> calls.call(() -> { count.incrementAndGet(); throw failure(status); }))
                    .isInstanceOfSatisfying(com.agilityhub.core.shared.domain.ApiException.class, e -> {
                        assertThat(e.code()).isEqualTo(status == 500 ? ErrorCode.INTERNAL_ERROR : ErrorCode.PROVIDER_CONFIG_INVALID);
                        assertThat(e.getMessage()).doesNotContain("private-provider-message");
                    });
            assertThat(count).hasValue(1);
        }
        var interrupted = new StripeCalls(millis -> { throw new InterruptedException(); });
        try { assertThatThrownBy(() -> interrupted.call(() -> { throw failure(429); })).isInstanceOf(com.agilityhub.core.shared.domain.ApiException.class); }
        finally { assertThat(Thread.interrupted()).isTrue(); }
    }
    private static ApiException failure(int status) { return new ApiException("private-provider-message", null, null, status, null); }

    @Test void T_12_17_round3_point3_authenticationAndRateLimitRejectionsProveNonExecution() {
        var calls = new StripeCalls(millis -> {});
        for (int status : List.of(400, 401, 403, 429, 500)) {
            var error = catchThrowable(() -> calls.call(() -> { throw failure(status); }));
            assertThat(error instanceof com.agilityhub.core.payments.application.PaymentNotSubmitted)
                    .as("HTTP %s definitively refused execution", status).isEqualTo(status == 401 || status == 403 || status == 429);
            assertThat(error.getMessage()).doesNotContain("private-provider-message");
        }
    }
    @Test void T_12_17_e8t11_point1_onlyContentRejectionsAreDefinitive() {
        var calls = new StripeCalls(millis -> {});
        for (int status : List.of(401, 403, 429)) {
            var error = catchThrowable(() -> calls.call(() -> { throw failure(status); }));
            assertThat(error).as("HTTP %s is an outage of the club's access", status)
                    .isInstanceOfSatisfying(com.agilityhub.core.payments.application.PaymentNotSubmitted.class, e -> assertThat(e.definitive()).isFalse());
        }
        var card = new com.stripe.exception.CardException("private-provider-message", null, "card_declined", null, null, null, 402, null);
        var invalid = new com.stripe.exception.InvalidRequestException("private-provider-message", null, null, "charge_already_refunded", 400, null);
        for (var rejection : List.<com.stripe.exception.StripeException>of(card, invalid)) {
            var error = catchThrowable(() -> calls.call(() -> { throw rejection; }));
            assertThat(error).as(rejection.getClass().getSimpleName())
                    .isInstanceOfSatisfying(com.agilityhub.core.payments.application.PaymentNotSubmitted.class, e -> {
                        assertThat(e.definitive()).isTrue();
                        assertThat(e.code()).isEqualTo(ErrorCode.PROVIDER_CONFIG_INVALID);
                    });
        }
    }
    @Test void T_12_17_round3_point3_serverFailureAfterRateLimitingStaysUncertain() {
        var calls = new StripeCalls(millis -> {}); var attempts = new AtomicInteger();
        var error = catchThrowable(() -> calls.call(() -> { throw failure(attempts.incrementAndGet() < 3 ? 429 : 500); }));
        assertThat(attempts).hasValue(3);
        assertThat(error).isNotInstanceOf(com.agilityhub.core.payments.application.PaymentNotSubmitted.class);
        assertThat(error).hasMessage("INTERNAL_ERROR");
    }
}
