package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.PushSubscriptionService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * The web-push devices of the caller's account (R-11-07), under the PUSH module (off → 404 MODULE_DISABLED, R-11-17). The
 * impersonation token is IMPERSONATION_DENIED: a device belongs to whoever holds it. E7-T02 sends; E7-T03 stores.
 */
@RestController
@RequiresModule(Module.PUSH)
@PreAuthorize("hasAnyRole('MEMBER','INSTRUCTOR','ADMIN')")
public class PushSubscriptionsController {
    static final String TENANT = " Requires PUSH." + MessageTemplatesController.TENANT;
    private final PushSubscriptionService subscriptions;
    public PushSubscriptionsController(PushSubscriptionService subscriptions) { this.subscriptions = subscriptions; }

    @PostMapping("/api/v1/push-subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, PUSH_SUBSCRIPTION_INVALID, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "subscribePush", description = "Roles: MEMBER, INSTRUCTOR, ADMIN (impersonation → IMPERSONATION_DENIED). Asked in context from 12 "
            + "(the push toggle or a reminder ≠ «Mai»), never at start-up: an upsert of the account's subscription by endpoint (its hash; one ACTIVE "
            + "subscription per endpoint and club), ACTIVE again if it had expired. A subscription never changes owner (E76): when another account's is "
            + "active on this browser it ends (EXPIRED, PushUnsubscribed for that account) and the caller gets a subscription of its own, a new id. "
            + "deviceLabel from the User-Agent when absent («iPhone · Safari»); PushSubscribed{accountId, endpoint = its SHA-256} when something changed. "
            + "An endpoint that is no https URL, or keys that are not base64url of a point on P-256 (65 bytes, uncompressed) and a 16-byte secret → "
            + "PUSH_SUBSCRIPTION_INVALID (422). The VAPID public key is GET /branding.pushPublicKey." + TENANT,
            responses = @ApiResponse(responseCode = "201", description = "PushSubscriptionCreated", useReturnTypeSchema = true))
    public PushSubscriptionCreated subscribePush(@Valid @RequestBody PushSubscriptionRequest request,
            @Parameter(hidden = true) @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return new PushSubscriptionCreated(subscriptions.subscribe(request.endpoint(), request.keys().p256dh(), request.keys().auth(), request.deviceLabel(), userAgent));
    }

    @DeleteMapping("/api/v1/push-subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({NOT_FOUND, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "unsubscribePush", description = "Roles: MEMBER, INSTRUCTOR, ADMIN, the caller's own subscription (impersonation → "
            + "IMPERSONATION_DENIED). Logout of the device: EXPIRED + expiredAt, PushUnsubscribed (idempotent); another account's or club's → 404. No body."
            + TENANT,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void unsubscribePush(@PathVariable String id) { subscriptions.unsubscribe(id); }
}
