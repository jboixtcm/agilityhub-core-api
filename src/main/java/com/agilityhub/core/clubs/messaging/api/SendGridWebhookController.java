package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.SendGridSignatureVerifier;
import com.agilityhub.core.clubs.messaging.application.SendGridWebhookService;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SendGridWebhookController {
    private final SendGridSignatureVerifier signatures;
    private final SendGridWebhookService service;
    private final ObjectMapper mapper;
    private final SecurityEvents securityEvents;
    public SendGridWebhookController(SendGridSignatureVerifier signatures, SendGridWebhookService service, ObjectMapper mapper,
            SecurityEvents securityEvents) {
        this.signatures = signatures; this.service = service; this.mapper = mapper; this.securityEvents = securityEvents;
    }
    @PostMapping(value = "/webhooks/email/sendgrid", consumes = "application/json")
    @SecurityRequirements
    @Operation(summary = "Receive signed SendGrid delivery events", description = "ECDSA signature over the timestamp header and raw request bytes. "
            + "No bearer token or request tenant is required. Events are matched to the stored notification, club and recipient; sg_event_id is idempotent.")
    @ApiResponse(responseCode = "200", description = "Processed or safely ignored", content = @Content)
    @ApiResponse(responseCode = "401", description = "WEBHOOK_SIGNATURE_INVALID")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
            array = @ArraySchema(schema = @Schema(implementation = SendGridEvent.class))))
    public void receive(@RequestHeader(value = "X-Twilio-Email-Event-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Twilio-Email-Event-Webhook-Signature", required = false) String signature,
            @io.swagger.v3.oas.annotations.Parameter(hidden = true) HttpServletRequest request) {
        byte[] body = body(request);
        if (body.length > MAX_BODY_BYTES) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        try { signatures.verify(body, timestamp, signature); }
        catch (ApiException invalid) {
            securityEvents.record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, null);
            throw invalid;
        }
        SendGridEvent[] events;
        try { events = mapper.readValue(body, SendGridEvent[].class); }
        catch (IOException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        if (events == null) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        for (var event : events) {
            if (event != null) { service.accept(event.id(), event.event(), event.notificationId(), event.clubId(), event.email(), event.type()); }
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendGridEvent(@JsonProperty("sg_event_id") String id, String event, String notificationId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String clubId, String email,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED, description = "A bounce's kind: `bounce` (hard) or `blocked` (soft, S11 R-11-08: no mark)")
            String type) {
        public SendGridEvent(String id, String event, String notificationId, String clubId, String email) { this(id, event, notificationId, clubId, email, null); }
    }

    /**
     * CFG-03: the body's cap, above SendGrid's batches (posted at about 768 KB); a longer body is refused before its signature is
     * checked, so an anonymous caller cannot make the server buffer it.
     */
    static final int MAX_BODY_BYTES = 1_048_576;

    /** Reads at most one byte past {@link #MAX_BODY_BYTES}; an empty or unreadable body → 400, as Spring's required body was. */
    static byte[] body(HttpServletRequest request) {
        try {
            byte[] body = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
            if (body.length == 0) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            return body;
        } catch (IOException unreadable) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
}
