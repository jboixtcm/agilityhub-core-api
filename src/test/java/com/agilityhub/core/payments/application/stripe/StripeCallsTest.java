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
}
