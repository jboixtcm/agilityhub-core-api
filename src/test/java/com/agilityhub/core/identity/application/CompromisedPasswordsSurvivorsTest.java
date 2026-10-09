package com.agilityhub.core.identity.application;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors (NO_COVERAGE) of {@link CompromisedPasswords#contains} (S01 T-01-09, the HIBP check fails open per A2):
 * a check interrupted while it waits for HIBP answers "not compromised", restores the thread's interrupt flag and cancels the
 * pending request. The HTTP client is a mock.
 */
class CompromisedPasswordsSurvivorsTest {

    @Test void T_01_09_anInterruptedHibpCheckFailsOpenKeepsTheInterruptAndCancelsTheRequest() {
        var client = mock(HttpClient.class);
        var pending = new CompletableFuture<HttpResponse<String>>();
        when(client.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        var check = new CompromisedPasswords(client, URI.create("https://example.test/range/"), Duration.ofSeconds(2));

        boolean compromised;
        boolean interrupted;
        Thread.currentThread().interrupt();
        try {
            compromised = check.contains("Fictional password");
        } finally {
            interrupted = Thread.interrupted();
        }

        assertThat(compromised).isFalse();
        assertThat(interrupted).isTrue();
        assertThat(pending.isCancelled()).isTrue();
    }
}
