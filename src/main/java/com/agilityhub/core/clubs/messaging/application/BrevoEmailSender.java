package com.agilityhub.core.clubs.messaging.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** Brevo's transactional API; acceptance is SENT, never evidence of delivery. */
public final class BrevoEmailSender implements EmailSender {
    public static final String SUPPRESSED = "Brevo recipient suppressed";
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final Duration timeout;
    private final String from;
    private final String name;
    private final Predicate<String> suppressed;

    public BrevoEmailSender(HttpClient client, ObjectMapper mapper, URI endpoint, String apiKey, Duration timeout,
            String from, String name, Predicate<String> suppressed) {
        this.client = client; this.mapper = mapper; this.endpoint = endpoint; this.apiKey = apiKey;
        this.timeout = timeout; this.from = from; this.name = name; this.suppressed = suppressed;
    }
    @Override public SendResult send(EmailMessage message) {
        if (suppressed.test(message.to())) { return SendResult.failed(SUPPRESSED); }
        try {
            var body = new LinkedHashMap<String, Object>();
            body.put("sender", Map.of("email", from, "name", name.isBlank() ? message.from().name() : name));
            body.put("to", List.of(Map.of("email", message.to())));
            body.put("subject", message.subject()); body.put("htmlContent", message.html()); body.put("textContent", message.text());
            var headers = new LinkedHashMap<>(message.headers());
            // Only opaque notification/tenant identifiers: never a credential or rendered message.
            var correlation = new LinkedHashMap<String, String>();
            for (String key : List.of("notificationId", "clubId")) {
                if (message.tags().containsKey(key)) { correlation.put(key, message.tags().get(key)); }
            }
            headers.put("X-Mailin-custom", Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(correlation)));
            body.put("headers", headers);
            if (message.replyTo() != null && !message.replyTo().isBlank()) { body.put("replyTo", Map.of("email", message.replyTo())); }
            var request = HttpRequest.newBuilder(endpoint).timeout(timeout).header("api-key", apiKey)
                    .header("Content-Type", "application/json").header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(body))).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 201) { return SendResult.failed("Brevo HTTP " + response.statusCode()); }
            if (response.body().length > 8192) { return SendResult.failed("Brevo invalid response"); }
            var json = mapper.readTree(response.body());
            if (json == null || !json.isObject()) { return SendResult.failed("Brevo invalid response"); }
            var id = json.path("messageId");
            if (!id.isTextual() || id.asText().isBlank() || id.asText().length() > 512) { return SendResult.failed("Brevo invalid response"); }
            return SendResult.sent(reference(id.asText()));
        } catch (HttpTimeoutException failure) { return SendResult.failed("Brevo timeout"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); return SendResult.failed("Brevo interrupted"); }
        catch (IOException failure) { return SendResult.failed("Brevo transport failure"); }
    }
    public static String reference(String id) {
        String value = id.strip();
        if (value.startsWith("<") && value.endsWith(">")) { value = value.substring(1, value.length() - 1); }
        return "brevo:" + value;
    }
}
