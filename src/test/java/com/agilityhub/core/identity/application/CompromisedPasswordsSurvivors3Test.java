package com.agilityhub.core.identity.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (session 10, NO_COVERAGE) of {@link CompromisedPasswords#contains} (S01 T-01-09, the HIBP check): a wait
 * that ends with an {@link Error} (not caught by the fail-open catches) still propagates the error and cancels the pending HIBP
 * request on its way out, so no request outlives the check. The HTTP client is a mock.
 */
class CompromisedPasswordsSurvivors3Test {

    /** A fictional failure of the waiting thread; an Error is the only throwable the two catch clauses let through. */
    static final class HibpWaitFailure extends Error {
        HibpWaitFailure() { super("fictional HIBP wait failure"); }
    }

    @Test void T_01_09_aHibpWaitEndingInAnErrorPropagatesItAndCancelsThePendingRequest() {
        var client = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<String>>() {
            @Override public HttpResponse<String> get(long timeout, TimeUnit unit) { throw new HibpWaitFailure(); }
        };
        when(client.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        var check = new CompromisedPasswords(client, URI.create("https://example.test/range/"), Duration.ofSeconds(2));

        assertThatThrownBy(() -> check.contains("Fictional password")).isInstanceOf(HibpWaitFailure.class);
        assertThat(pending.isCancelled()).isTrue();
    }
}
