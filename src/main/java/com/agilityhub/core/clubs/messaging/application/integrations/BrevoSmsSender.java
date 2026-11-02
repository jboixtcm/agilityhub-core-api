package com.agilityhub.core.clubs.messaging.application.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Brevo transactional SMS. Errors never contain phone numbers, content or provider response bodies. */
public final class BrevoSmsSender implements SmsSender {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;

    public BrevoSmsSender(HttpClient client, ObjectMapper mapper, URI endpoint, String apiKey, Duration timeout) {
        this.client = client;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.timeout = timeout;
    }

    @Override public SendResult send(SmsMessage message) {
        var body = mapper.createObjectNode();
        body.put("sender", message.senderId());
        body.put("recipient", message.to().startsWith("+") ? message.to().substring(1) : message.to());
        body.put("type", "transactional");
        body.put("content", message.body());
        var request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("api-key", apiKey).header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 201) { return SendResult.accepted(providerRef(response.body())); }
            String error = "Brevo SMS HTTP " + status;
            return status == 429 || status >= 500 ? SendResult.retryable(error) : SendResult.failed(error);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SendResult.retryable("Brevo SMS interrupted");
        } catch (IOException failure) {
            return SendResult.retryable("Brevo SMS transport failure");
        }
    }

    private String providerRef(byte[] body) {
        // The provider has already accepted the SMS even if the optional receipt cannot be parsed.
        try {
            var json = mapper.readTree(body);
            var id = json == null ? null : json.get("messageId");
            return id != null && id.isIntegralNumber() ? "brevo-sms:" + id.asText() : null;
        } catch (IOException invalidReceipt) {
            return null;
        }
    }
}
