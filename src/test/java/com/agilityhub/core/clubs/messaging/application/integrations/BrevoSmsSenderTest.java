package com.agilityhub.core.clubs.messaging.application.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BrevoSmsSenderTest {
    @ParameterizedTest @CsvSource({"201,accepted", "400,failed", "429,retry", "503,retry"})
    void usesTransactionalEndpointAndClassifiesResponses(int status, String outcome) throws Exception {
        var mapper = new ObjectMapper();
        var captured = new CompletableFuture<Map<String, String>>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/transactionalSMS/send", exchange -> {
            captured.complete(Map.of("method", exchange.getRequestMethod(), "key", exchange.getRequestHeaders().getFirst("api-key"),
                    "body", new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] response = "{\"messageId\":1511882900100020}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, response.length);
            try (exchange) { exchange.getResponseBody().write(response); }
        });
        server.start();
        try {
            var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/transactionalSMS/send");
            var sender = new BrevoSmsSender(HttpClient.newHttpClient(), mapper, uri, "test-key", Duration.ofSeconds(3));
            var result = sender.send(new SmsMessage("+34600000001", "Prova", "TestClub", Map.of()));
            var request = captured.join();
            assertThat(request).containsEntry("method", "POST").containsEntry("key", "test-key");
            assertThat(mapper.readTree(request.get("body"))).isEqualTo(mapper.readTree(
                    "{\"type\":\"transactional\",\"content\":\"Prova\",\"sender\":\"TestClub\",\"recipient\":\"34600000001\"}"));
            assertThat(result.ok()).isEqualTo(outcome.equals("accepted"));
            assertThat(result.retryable()).isEqualTo(outcome.equals("retry"));
            if (result.ok()) { assertThat(result.providerRef()).isEqualTo("brevo-sms:1511882900100020"); }
            else { assertThat(result.error()).isEqualTo("Brevo SMS HTTP " + status); }
        } finally { server.stop(0); }
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    void transportErrorsAreRedactedAndAnAcceptedSmsWithAnInvalidReceiptIsNotRetried() throws Exception {
        var client = mock(HttpClient.class);
        var sender = new BrevoSmsSender(client, new ObjectMapper(), URI.create("https://api.example.test"), "test-key", Duration.ofSeconds(1));
        var message = new SmsMessage("34600000001", "Test", "Club", Map.of());
        var response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(201);
        when(response.body()).thenReturn("{}".getBytes(StandardCharsets.UTF_8), "invalid".getBytes(StandardCharsets.UTF_8), new byte[0]);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        for (int i = 0; i < 3; i++) { assertThat(sender.send(message)).isEqualTo(SendResult.accepted(null)); }
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("sensitive"));
        assertThat(sender.send(message)).isEqualTo(SendResult.retryable("Brevo SMS transport failure"));
        doThrow(new InterruptedException()).when(client).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        try {
            assertThat(sender.send(message)).isEqualTo(SendResult.retryable("Brevo SMS interrupted"));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
