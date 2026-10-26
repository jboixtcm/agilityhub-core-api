package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.BrevoWebhookAuthentication;
import com.agilityhub.core.clubs.messaging.application.BrevoWebhookService;
import com.agilityhub.core.shared.application.SecurityEvents;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import java.io.IOException;
import org.springframework.web.bind.annotation.*;

@RestController
public class BrevoWebhookController {
    private final BrevoWebhookAuthentication authentication;
    private final BrevoWebhookService service;
    private final ObjectMapper mapper;
    private final SecurityEvents security;
    public BrevoWebhookController(BrevoWebhookAuthentication authentication, BrevoWebhookService service, ObjectMapper mapper, SecurityEvents security) {
        this.authentication = authentication; this.service = service; this.mapper = mapper; this.security = security;
    }
    @PostMapping(value = "/webhooks/email/brevo", consumes = "application/json")
    @SecurityRequirements
    @Operation(operationId = "brevoWebhook", summary = "Receive authenticated Brevo transactional events", description = "Requires the dedicated webhook bearer secret over HTTPS, not an account JWT. "
            + "Accepts one event or a batch. Events correlate opaque custom metadata, the stored tenant, recipient and provider reference. Replays are idempotent.")
    @ApiResponse(responseCode = "200", description = "Processed or safely ignored", content = @Content)
    @ApiResponse(responseCode = "401", description = "WEBHOOK_SIGNATURE_INVALID")
    public void receive(@RequestHeader(value = "Authorization", required = false) String authorization,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
                    content = @Content(mediaType = "application/json", schema = @Schema(anyOf = {BrevoEvent.class, BrevoEvent[].class})))
            @RequestBody byte[] body) {
        try { authentication.verify(authorization); }
        catch (ApiException invalid) { security.record(SecurityEvents.Type.WEBHOOK_SIGNATURE_INVALID, null, null); throw invalid; }
        if (body.length > 1_048_576) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
        try {
            var root = mapper.readTree(body);
            if (root == null || !root.isObject() && !root.isArray() || root.isArray() && root.size() > 1000) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
            var items = root.isArray() ? root : mapper.createArrayNode().add(root);
            for (var item : items) { if (!item.isObject()) { throw new ApiException(ErrorCode.VALIDATION_ERROR); } }
            for (var item : items) { service.accept(text(item, "event"), text(item, "message-id"), text(item, "email"), text(item, "X-Mailin-custom")); }
        } catch (IOException invalid) { throw new ApiException(ErrorCode.VALIDATION_ERROR); }
    }
    public record BrevoEvent(String event, @JsonProperty("message-id") String messageId, String email,
            @JsonProperty("X-Mailin-custom") String custom) { }
    private static String text(com.fasterxml.jackson.databind.JsonNode node, String key) { return node.path(key).isTextual() ? node.path(key).asText() : null; }
}
