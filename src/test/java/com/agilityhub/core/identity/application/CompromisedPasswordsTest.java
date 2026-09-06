package com.agilityhub.core.identity.application;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CompromisedPasswordsTest {
    @Test void T_01_09_hibpSynchronousTransportFailureAlsoFailsOpen() {
        var client = org.mockito.Mockito.mock(HttpClient.class);
        org.mockito.Mockito.when(client.sendAsync(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any()))
                .thenThrow(new java.util.concurrent.RejectedExecutionException("transport closed"));
        assertThat(new CompromisedPasswords(client, URI.create("https://example.test/"), Duration.ofSeconds(2))
                .contains("Fictional password")).isFalse();
    }

    @Test void T_01_09_hibpSendsOnlyFiveHashCharactersWithPaddingAndRejectsPositiveCounts() throws Exception {
        String password = "Fictional password for HIBP test";
        String digest = HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8)));
        var request = new AtomicReference<String>();
        var padding = new AtomicReference<String>();
        var response = new AtomicReference<>(digest.substring(5) + ":5\r\n" + "0".repeat(35) + ":0\r\n");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/range/", exchange -> {
            request.set(exchange.getRequestURI().toString()); padding.set(exchange.getRequestHeaders().getFirst("Add-Padding"));
            var bytes = response.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try (var client = HttpClient.newHttpClient()) {
            var check = new CompromisedPasswords(client, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/range/"), Duration.ofSeconds(2));
            assertThat(check.contains(password)).isTrue();
            assertThat(request.get()).isEqualTo("/range/" + digest.substring(0, 5)).doesNotContain(password, digest);
            assertThat(padding.get()).isEqualTo("true");
            response.set(digest.substring(5) + ":0\nmalformed\n" + digest.substring(5) + ":invalid\n" + "F".repeat(35) + ":5");
            assertThat(check.contains(password)).isFalse();
        } finally { server.stop(0); }
    }
    @Test void T_01_09_hibpFailureAndTimeoutAreFailOpenWithoutLeakingRequestData() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { exchange.sendResponseHeaders(503, -1); exchange.close(); }); server.start();
        try (var client = HttpClient.newHttpClient()) {
            var check = new CompromisedPasswords(client, URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(2));
            assertThat(check.contains("Fictional password")).isFalse();
        } finally { server.stop(0); }
        var client = org.mockito.Mockito.mock(HttpClient.class);
        var pending = new java.util.concurrent.CompletableFuture<java.net.http.HttpResponse<String>>();
        org.mockito.Mockito.when(client.sendAsync(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.<java.net.http.HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
        assertThat(new CompromisedPasswords(client, URI.create("https://example.test/"), Duration.ofMillis(20)).contains("Fictional password")).isFalse();
        assertThat(pending.isCancelled()).isTrue();
    }
}
