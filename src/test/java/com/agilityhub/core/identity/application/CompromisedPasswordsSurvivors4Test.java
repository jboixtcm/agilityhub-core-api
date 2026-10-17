package com.agilityhub.core.identity.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT session-10 survivors of {@link CompromisedPasswords} (S01 R-01-05 and A2, T-01-09): the HIBP check fails open, and a
 * request that is still pending when the check gives up is cancelled; a request that could not even start leaves nothing to
 * cancel and still answers "not compromised". The HTTP client is a mock; no network.
 */
class CompromisedPasswordsSurvivors4Test {
    final HttpClient client = mock(HttpClient.class);
    final CompromisedPasswords passwords = new CompromisedPasswords(client, URI.create("https://hibp.example.test/range/"), Duration.ofMillis(20));

    @Test void T_01_09_aLookupStillPendingAtTheTimeoutIsCancelledAndTheCheckFailsOpen() {
        var pending = new CompletableFuture<HttpResponse<String>>();
        when(client.<String>sendAsync(any(), any())).thenReturn(pending);

        assertThat(passwords.contains("fictional-password-1")).isFalse();
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test void T_01_09_anInterruptedLookupIsCancelledFailsOpenAndKeepsTheInterrupt() {
        var pending = new CompletableFuture<HttpResponse<String>>();
        when(client.<String>sendAsync(any(), any())).thenReturn(pending);
        Thread.currentThread().interrupt();
        try {
            assertThat(passwords.contains("fictional-password-4")).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(pending.isCancelled()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test void T_01_09_aLookupThatCannotStartFailsOpenWithNothingToCancel() {
        when(client.<String>sendAsync(any(), any())).thenThrow(new IllegalArgumentException("fictional client failure"));

        assertThat(passwords.contains("fictional-password-2")).isFalse();
    }

    @Test void T_01_09_aCompletedLookupIsNotCancelled() {
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("0000000000000000000000000000000000A:1");
        var done = spy(CompletableFuture.completedFuture(response));
        when(client.<String>sendAsync(any(), any())).thenReturn(done);

        assertThat(passwords.contains("fictional-password-3")).isFalse();
        verify(done, never()).cancel(anyBoolean());
    }
}
