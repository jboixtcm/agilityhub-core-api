package com.agilityhub.core.clubs.messaging.application.integrations;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Twilio Messages API (`POST /2010-04-01/Accounts/{AccountSid}/Messages.json`, form-encoded, HTTP basic with the account
 * SID and the auth token from `TWILIO_ACCOUNT_SID` / `TWILIO_AUTH_TOKEN`): `From` = `messaging.sms.senderId`, or the
 * `MessagingServiceSid` of `TWILIO_MESSAGING_SERVICE_SID` when it is set. Accepted (201) → the message SID as provider
 * reference; 429, 5xx, a timeout or a transport failure → retryable; any other status → final (an invalid number is a 400,
 * R-11-06). Twilio is a processor (S14 R-14-18): no phone, body or response text ever reaches a log or an error.
 */
public final class TwilioSmsSender implements SmsSender {
    private final HttpClient client; private final ObjectMapper mapper; private final URI base;
    private final String accountSid, authToken, messagingServiceSid; private final Duration timeout;

    public TwilioSmsSender(HttpClient client, ObjectMapper mapper, URI base, String accountSid, String authToken, String messagingServiceSid, Duration timeout) {
        this.client = client; this.mapper = mapper; this.base = base; this.accountSid = accountSid; this.authToken = authToken;
        this.messagingServiceSid = messagingServiceSid == null || messagingServiceSid.isBlank() ? null : messagingServiceSid; this.timeout = timeout;
    }

    @Override public SendResult send(SmsMessage message) {
        var form = new LinkedHashMap<String, String>();
        form.put("To", message.to());
        if (messagingServiceSid != null) { form.put("MessagingServiceSid", messagingServiceSid); } else { form.put("From", message.senderId()); }
        form.put("Body", message.body());
        String body = form.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        String credentials = Base64.getEncoder().encodeToString((accountSid + ":" + authToken).getBytes(StandardCharsets.UTF_8));
        try {
            var request = HttpRequest.newBuilder(base.resolve("/2010-04-01/Accounts/" + URLEncoder.encode(accountSid, StandardCharsets.UTF_8) + "/Messages.json"))
                    .timeout(timeout).header("Authorization", "Basic " + credentials).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == 200 || status == 201) { return SendResult.accepted(sid(response.body())); }
            if (status == 429 || status >= 500) { return SendResult.retryable("Twilio HTTP " + status); }
            return SendResult.failed("Twilio HTTP " + status);
        } catch (HttpTimeoutException timedOut) {
            return SendResult.retryable("Twilio timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return SendResult.retryable("Twilio interrupted");
        } catch (IOException transport) {
            return SendResult.retryable("Twilio transport failure");
        }
    }

    private String sid(byte[] body) {
        try {
            Map<?, ?> json = mapper.readValue(body, Map.class);
            return json.get("sid") instanceof String sid ? sid : null;
        } catch (IOException unreadable) { return null; }
    }
}
