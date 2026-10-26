package com.agilityhub.core.clubs.messaging.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrevoEmailSenderTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String key = UUID.randomUUID().toString();
    private CompletableFuture<Captured> captured;
    private int status = 201;
    private String response = "{\"messageId\":\"<provider@example.test>\"}";
    private long delay;
    record Captured(boolean authenticated, byte[] body) { }
    @BeforeEach void start() throws Exception {
        captured = new CompletableFuture<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v3/smtp/email", exchange -> {
            captured.complete(new Captured(key.equals(exchange.getRequestHeaders().getFirst("api-key")), exchange.getRequestBody().readAllBytes()));
            try { if (delay > 0) { Thread.sleep(delay); } } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private BrevoEmailSender sender(Duration timeout, java.util.function.Predicate<String> suppressed) {
        return new BrevoEmailSender(HttpClient.newHttpClient(), mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/smtp/email"), key, timeout,
                "sender@example.test", "Example Operator", suppressed);
    }
    private EmailMessage message(String reply) {
        return new EmailMessage("recipient@example.test", "Subject", "<p>Body</p>", "Body",
                new EmailMessage.Address("club@example.test", "Example Club"), reply, Locale.ENGLISH,
                Map.of("notificationId", "notification-example", "clubId", "club-a", "credential", "never-forward"),
                Map.of("List-Unsubscribe", "<https://club.example.test/unsubscribe>"));
    }
    @Test void T_11_25_apiRequestPreservesRenderingUsesVerifiedSenderAndOpaqueCorrelation() throws Exception {
        assertThat(sender(Duration.ofSeconds(2), address -> false).send(message("reply@example.test")))
                .isEqualTo(EmailSender.SendResult.sent("brevo:provider@example.test"));
        assertThat(captured.get().authenticated()).isTrue();
        var body = mapper.readTree(captured.get().body());
        assertThat(body.at("/sender/email").asText()).isEqualTo("sender@example.test");
        assertThat(body.at("/sender/name").asText()).isEqualTo("Example Operator");
        assertThat(body.at("/to/0/email").asText()).isEqualTo("recipient@example.test");
        assertThat(body.at("/replyTo/email").asText()).isEqualTo("reply@example.test");
        assertThat(body.path("subject").asText()).isEqualTo("Subject");
        assertThat(body.path("htmlContent").asText()).isEqualTo("<p>Body</p>");
        assertThat(body.path("textContent").asText()).isEqualTo("Body");
        assertThat(body.at("/headers/List-Unsubscribe").asText()).isEqualTo("<https://club.example.test/unsubscribe>");
        var custom = mapper.readTree(Base64.getUrlDecoder().decode(body.at("/headers/X-Mailin-custom").asText()));
        assertThat(custom.path("notificationId").asText()).isEqualTo("notification-example");
        assertThat(custom.path("clubId").asText()).isEqualTo("club-a");
        assertThat(custom.has("credential")).isFalse();
        assertThat(body.path("headers").has("X-Mailin-Track")).isFalse();
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 429, 500, 503})
    void T_11_09_providerFailuresNeverEchoResponseOrCredentials(int code) throws Exception {
        status = code; response = "private credential recipient@example.test";
        assertThat(sender(Duration.ofSeconds(2), address -> false).send(message(null)))
                .isEqualTo(EmailSender.SendResult.failed("Brevo HTTP " + code));
        assertThat(mapper.readTree(captured.get().body()).has("replyTo")).isFalse();
    }
    @Test void T_11_08_suppressedRecipientNeverCallsProvider() {
        assertThat(sender(Duration.ofSeconds(2), address -> true).send(message(null)).error()).isEqualTo(BrevoEmailSender.SUPPRESSED);
        assertThat(captured.isDone()).isFalse();
    }
    @Test void T_11_09_timeoutAndTransportFailuresAreSafe() throws Exception {
        delay = 600;
        assertThat(sender(Duration.ofMillis(100), address -> false).send(message(null)).error()).isEqualTo("Brevo timeout");
        var client = mock(HttpClient.class);
        var sender = new BrevoEmailSender(client, mapper, URI.create("https://api.example.test/v3/smtp/email"), key,
                Duration.ofSeconds(1), "sender@example.test", "", address -> false);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("private"));
        assertThat(sender.send(message(null)).error()).isEqualTo("Brevo transport failure");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try { assertThat(sender.send(message(null)).error()).isEqualTo("Brevo interrupted"); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }
    @ParameterizedTest @ValueSource(strings = {"null", "{}", "{\"messageId\":\"\"}", "[]"})
    void T_11_09_missingMessageReferenceIsNotClaimed(String body) {
        response = body;
        assertThat(sender(Duration.ofSeconds(2), address -> false).send(message(null)).error()).isEqualTo("Brevo invalid response");
    }
}
