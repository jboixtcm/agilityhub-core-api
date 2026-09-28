package com.agilityhub.core.clubs.messaging.application.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * E7-T02 step 6 against a local HTTP server (no Twilio credentials exist yet, decision E13): the Messages API request, the
 * accepted SID, the retryable (429, 5xx, timeout, transport) and final (other 4xx) answers — none of which echoes the
 * phone, the text or the provider's body (R-14-18) — and the other `SmsSender`s: the non-production allow-list guard, the
 * log sender of `local` and the test double.
 */
class TwilioSmsSenderTest {
    private static final String SID = "AC00000000000000000000000000000000", TOKEN = "test-token-not-a-secret";
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private CompletableFuture<Captured> captured;
    private int responseStatus = 201;
    private String responseBody = "{\"sid\":\"SM00000000000000000000000000000001\",\"status\":\"queued\"}";
    private long delay;
    record Captured(String method, String path, String contentType, String authorization, Map<String, String> form) { }

    @BeforeEach void start() throws Exception {
        captured = new CompletableFuture<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/2010-04-01/Accounts/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var form = Arrays.stream(body.split("&")).map(pair -> pair.split("=", 2)).collect(Collectors.toMap(
                    pair -> URLDecoder.decode(pair[0], StandardCharsets.UTF_8), pair -> URLDecoder.decode(pair[1], StandardCharsets.UTF_8), (a, b) -> b, LinkedHashMap::new));
            captured.complete(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestHeaders().getFirst("Authorization"), form));
            try { if (delay > 0) { Thread.sleep(delay); } } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] answer = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(responseStatus, answer.length); exchange.getResponseBody().write(answer); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private TwilioSmsSender sender(String service, Duration timeout) {
        return new TwilioSmsSender(HttpClient.newHttpClient(), mapper, URI.create("http://127.0.0.1:" + server.getAddress().getPort()), SID, TOKEN, service, timeout);
    }
    private static SmsMessage message() {
        return new SmsMessage("+34600000001", "Canic: classe anul.lada dimecres 12 a les 18:50", "Canic", Map.of("clubId", "club-a", "notificationId", "notification-a"));
    }

    @Test void T_11_06_messagesApiRequestWithTheSenderIdAndTheAcceptedSid() throws Exception {
        var result = sender(null, Duration.ofSeconds(2)).send(message());
        assertThat(result).isEqualTo(SendResult.accepted("SM00000000000000000000000000000001"));
        assertThat(result.ok()).isTrue(); assertThat(result.refusedByGuard()).isFalse();
        var request = captured.get();
        assertThat(request.method()).isEqualTo("POST"); assertThat(request.path()).isEqualTo("/2010-04-01/Accounts/" + SID + "/Messages.json");
        assertThat(request.contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(request.authorization()).isEqualTo("Basic " + Base64.getEncoder().encodeToString((SID + ":" + TOKEN).getBytes(StandardCharsets.UTF_8)));
        assertThat(request.form()).containsExactly(Map.entry("To", "+34600000001"), Map.entry("From", "Canic"),
                Map.entry("Body", "Canic: classe anul.lada dimecres 12 a les 18:50"));
    }

    @Test void T_11_06_aMessagingServiceReplacesTheSenderId() throws Exception {
        responseStatus = 200; responseBody = "not json";
        var result = sender("MG00000000000000000000000000000001", Duration.ofSeconds(2)).send(message());
        assertThat(result.ok()).isTrue(); assertThat(result.providerRef()).isNull();
        assertThat(captured.get().form()).containsEntry("MessagingServiceSid", "MG00000000000000000000000000000001").doesNotContainKey("From");
        assertThat(sender("  ", Duration.ofSeconds(2)).send(message()).ok()).isTrue();
    }

    @ParameterizedTest @CsvSource({"400,false", "401,false", "404,false", "429,true", "500,true", "503,true"})
    void T_11_09_providerAnswersAreRetryableOrFinalWithoutEchoingTheRequest(int status, boolean retryable) {
        responseStatus = status; responseBody = "{\"message\":\"The 'To' number +34600000001 is not valid\"}";
        var result = sender(null, Duration.ofSeconds(2)).send(message());
        assertThat(result.ok()).isFalse(); assertThat(result.retryable()).isEqualTo(retryable);
        assertThat(result.error()).isEqualTo("Twilio HTTP " + status).doesNotContain("+34600000001");
    }

    @Test void T_11_09_timeoutTransportAndInterruptAreRetryable() throws Exception {
        delay = 600;
        assertThat(sender(null, Duration.ofMillis(100)).send(message())).isEqualTo(SendResult.retryable("Twilio timeout"));
        var client = mock(HttpClient.class);
        var mocked = new TwilioSmsSender(client, mapper, URI.create("https://api.example.test"), SID, TOKEN, null, Duration.ofSeconds(1));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("private body +34600000001"));
        assertThat(mocked.send(message())).isEqualTo(SendResult.retryable("Twilio transport failure"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try { assertThat(mocked.send(message())).isEqualTo(SendResult.retryable("Twilio interrupted")); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }

    @Test void T_11_06_outsideProductionOnlyTheAllowedNumbersReachTheProvider() {
        var fake = new FakeSmsSender();
        var guard = new AllowListSmsSender(fake, AllowListSmsSender.parse(" +34600000001 , ,+34 600 000 003"));
        assertThat(AllowListSmsSender.parse(null)).isEmpty(); assertThat(AllowListSmsSender.parse("  ")).isEmpty();
        assertThat(AllowListSmsSender.parse("+34600000001,+34 600 000 003")).containsExactlyInAnyOrder("+34600000001", "+34600000003");
        assertThat(guard.send(message()).ok()).isTrue();
        var refused = guard.send(new SmsMessage("+34600000002", "text", "Canic", Map.of()));
        assertThat(refused).isEqualTo(SendResult.notAllowed()); assertThat(refused.refusedByGuard()).isTrue(); assertThat(refused.retryable()).isFalse();
        assertThat(fake.messages()).extracting(SmsMessage::to).containsExactly("+34600000001");
        assertThat(guard.delegate()).isSameAs(fake);
        // Empty list = nobody.
        assertThat(new AllowListSmsSender(fake, AllowListSmsSender.parse("")).send(message())).isEqualTo(SendResult.notAllowed());
        assertThatThrownBy(() -> new AllowListSmsSender(null, java.util.Set.of())).isInstanceOf(NullPointerException.class);
    }

    @Test void R_14_18_theLocalAndTestSendersNeverPrintAPhoneOrAText() {
        var local = new LogSmsSender().send(message());
        assertThat(local.ok()).isTrue(); assertThat(local.providerRef()).startsWith("log-");
        assertThat(message().toString()).isEqualTo("SmsMessage[redacted]").doesNotContain("+34600000001");
        assertThat(new SmsMessage("+34600000001", "x", null, null).tags()).isEmpty();
        assertThatThrownBy(() -> new SmsMessage(null, "x", null, null)).isInstanceOf(NullPointerException.class);
        var fake = new FakeSmsSender();
        fake.failNext(SendResult.retryable("Twilio HTTP 503"));
        assertThat(fake.send(message())).isEqualTo(SendResult.retryable("Twilio HTTP 503"));
        fake.failNext(SendResult.accepted("ignored"));
        assertThat(fake.send(message()).providerRef()).startsWith("fake-sms-");
        assertThat(fake.lastTo("+34600000001").body()).startsWith("Canic");
        assertThatThrownBy(() -> fake.lastTo("+34600000009")).isInstanceOf(java.util.NoSuchElementException.class);
        fake.clear(); assertThat(fake.messages()).isEmpty();
        assertThat(SendResult.failed("x").retryable()).isFalse(); assertThat(SendResult.retryable("x").retryable()).isTrue();
    }
}
