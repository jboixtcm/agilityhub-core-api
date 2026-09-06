package com.agilityhub.core.clubs.messaging.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SendGridEmailSender implements EmailSender {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    public SendGridEmailSender(HttpClient client, ObjectMapper mapper, URI endpoint, String apiKey, Duration timeout) {
        this.client = client; this.mapper = mapper; this.endpoint = endpoint; this.apiKey = apiKey; this.timeout = timeout;
    }
    @Override public SendResult send(EmailMessage message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("personalizations", List.of(Map.of("to", List.of(Map.of("email", message.to())))));
        body.put("from", Map.of("email", message.from().email(), "name", message.from().name()));
        body.put("subject", message.subject());
        body.put("content", List.of(Map.of("type", "text/plain", "value", message.text()),
                Map.of("type", "text/html", "value", message.html())));
        body.put("custom_args", message.tags());
        // Magic links must not be rewritten or tracked by the provider.
        body.put("tracking_settings", Map.of("click_tracking", Map.of("enable", false, "enable_text", false),
                "open_tracking", Map.of("enable", false)));
        if (message.replyTo() != null && !message.replyTo().isBlank()) {
            body.put("reply_to", Map.of("email", message.replyTo()));
        }
        try {
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 202 ? SendResult.sent(response.headers().firstValue("X-Message-Id").orElse(null))
                    : SendResult.failed("SendGrid HTTP " + response.statusCode());
        } catch (HttpTimeoutException failure) {
            return SendResult.failed("SendGrid timeout");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return SendResult.failed("SendGrid interrupted");
        } catch (IOException failure) {
            // Provider bodies and exception messages can echo credentials or personal data.
            return SendResult.failed("SendGrid transport failure");
        }
    }
}
