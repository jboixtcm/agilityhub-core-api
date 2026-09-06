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
import java.io.IOException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
    public void receive(@RequestHeader(value = "X-Twilio-Email-Event-Webhook-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Twilio-Email-Event-Webhook-Signature", required = false) String signature,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = SendGridEvent.class)))) @RequestBody byte[] body) {
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
            if (event != null) { service.accept(event.id(), event.event(), event.notificationId(), event.clubId(), event.email()); }
        }
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SendGridEvent(@JsonProperty("sg_event_id") String id, String event, String notificationId,
            @Schema(requiredMode = Schema.RequiredMode.NOT_REQUIRED) String clubId, String email) { }
}
