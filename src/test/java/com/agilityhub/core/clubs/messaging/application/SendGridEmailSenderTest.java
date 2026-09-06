package com.agilityhub.core.clubs.messaging.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SendGridEmailSenderTest {
    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private String key;
    private CompletableFuture<Captured> captured;
    private int responseStatus = 202;
    private long delay;
    record Captured(String method, String path, String contentType, boolean authorized, byte[] body) { }
    @BeforeEach void start() throws Exception {
        key = java.util.UUID.randomUUID().toString(); captured = new CompletableFuture<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/v3/mail/send", exchange -> {
            captured.complete(new Captured(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    ("Bearer " + key).equals(exchange.getRequestHeaders().getFirst("Authorization")), exchange.getRequestBody().readAllBytes()));
            try { if (delay > 0) { Thread.sleep(delay); } } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.getResponseHeaders().set("X-Message-Id", "provider-example-id");
            exchange.sendResponseHeaders(responseStatus, -1); exchange.close();
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private SendGridEmailSender sender(Duration timeout) {
        return new SendGridEmailSender(HttpClient.newHttpClient(), mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v3/mail/send"), key, timeout);
    }
    private EmailMessage message(String reply) {
        return new EmailMessage("recipient@example.test", "Subject", "<p>Body</p>", "Body",
                new EmailMessage.Address("sender@example.test", "Example Club"), reply, Locale.ENGLISH,
                Map.of("notificationId", "notification-example", "clubId", "club-a"));
    }
    @Test void T_11_25_sendGridRequestAndAcceptedMessageId() throws Exception {
        assertThat(sender(Duration.ofSeconds(2)).send(message("reply@example.test")))
                .isEqualTo(EmailSender.SendResult.sent("provider-example-id"));
        var request = captured.get();
        assertThat(request.method()).isEqualTo("POST"); assertThat(request.path()).isEqualTo("/v3/mail/send");
        assertThat(request.authorized()).isTrue(); assertThat(request.contentType()).isEqualTo("application/json");
        var body = mapper.readTree(request.body());
        assertThat(body.at("/personalizations/0/to/0/email").asText()).isEqualTo("recipient@example.test");
        assertThat(body.at("/from/email").asText()).isEqualTo("sender@example.test");
        assertThat(body.at("/from/name").asText()).isEqualTo("Example Club");
        assertThat(body.at("/reply_to/email").asText()).isEqualTo("reply@example.test");
        assertThat(body.path("subject").asText()).isEqualTo("Subject");
        assertThat(body.at("/content/0/type").asText()).isEqualTo("text/plain");
        assertThat(body.at("/content/0/value").asText()).isEqualTo("Body");
        assertThat(body.at("/content/1/type").asText()).isEqualTo("text/html");
        assertThat(body.at("/content/1/value").asText()).isEqualTo("<p>Body</p>");
        assertThat(body.at("/custom_args/clubId").asText()).isEqualTo("club-a");
        assertThat(body.at("/custom_args/notificationId").asText()).isEqualTo("notification-example");
        assertThat(body.at("/tracking_settings/click_tracking/enable").asBoolean()).isFalse();
        assertThat(body.at("/tracking_settings/open_tracking/enable").asBoolean()).isFalse();
        assertThat(body.has("asm")).isFalse();
    }
    @ParameterizedTest @ValueSource(ints = {400, 401, 429, 500, 503})
    void T_11_09_providerErrorsBecomeSafeFailures(int status) throws Exception {
        responseStatus = status;
        var result = sender(Duration.ofSeconds(2)).send(message(null));
        assertThat(result.sent()).isFalse(); assertThat(result.providerMessageId()).isNull();
        assertThat(result.error()).isEqualTo("SendGrid HTTP " + status);
        assertThat(mapper.readTree(captured.get().body()).has("reply_to")).isFalse();
    }
    @Test void T_11_09_timeoutIsHandled() {
        delay = 600;
        assertThat(sender(Duration.ofMillis(100)).send(message(""))).isEqualTo(EmailSender.SendResult.failed("SendGrid timeout"));
    }
    @Test void T_11_09_transportAndInterruptDoNotExposeRequestData() throws Exception {
        var client = mock(HttpClient.class);
        var sender = new SendGridEmailSender(client, mapper, URI.create("https://api.example.test/v3/mail/send"), key, Duration.ofSeconds(1));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new java.io.IOException("private body"));
        assertThat(sender.send(message(null)).error()).isEqualTo("SendGrid transport failure");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try { assertThat(sender.send(message(null)).error()).isEqualTo("SendGrid interrupted"); assertThat(Thread.currentThread().isInterrupted()).isTrue(); }
        finally { Thread.interrupted(); }
    }
}
