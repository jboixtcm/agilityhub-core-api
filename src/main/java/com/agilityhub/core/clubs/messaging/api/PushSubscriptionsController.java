package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.MessagingContractAccess;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.RequiresModule;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * The web-push devices of the caller's account (R-11-07), under the PUSH module (off → 404 MODULE_DISABLED, R-11-17). The
 * impersonation token is IMPERSONATION_DENIED: a device belongs to whoever holds it. 501 NOT_IMPLEMENTED after the guards
 * until E7-T03 (E7-T02 sends).
 */
@RestController
@RequiresModule(Module.PUSH)
@PreAuthorize("hasAnyRole('MEMBER','INSTRUCTOR','ADMIN')")
public class PushSubscriptionsController {
    static final String STUB = " Requires PUSH." + MessageTemplatesController.STUB;
    private final MessagingContractAccess access;
    public PushSubscriptionsController(MessagingContractAccess access) { this.access = access; }

    @PostMapping("/api/v1/push-subscriptions")
    @ResponseStatus(HttpStatus.CREATED)
    @ContractErrors({VALIDATION_ERROR, PUSH_SUBSCRIPTION_INVALID, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "subscribePush", description = "Roles: MEMBER, INSTRUCTOR, ADMIN (impersonation → IMPERSONATION_DENIED). Asked in context from 12 "
            + "(the push toggle or a reminder ≠ «Mai»), never at start-up: an upsert of the account's subscription by endpoint (unique per club through its "
            + "hash), ACTIVE again if it had expired; PushSubscribed{accountId, endpoint = its SHA-256}. An endpoint that is no https URL or keys that are no "
            + "base64url → PUSH_SUBSCRIPTION_INVALID (422). The VAPID public key is GET /branding.pushPublicKey." + STUB,
            responses = @ApiResponse(responseCode = "201", description = "PushSubscriptionCreated", useReturnTypeSchema = true))
    public PushSubscriptionCreated subscribePush(@Valid @RequestBody PushSubscriptionRequest request) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @DeleteMapping("/api/v1/push-subscriptions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ContractErrors({NOT_FOUND, MODULE_DISABLED, IMPERSONATION_DENIED})
    @Operation(summary = "unsubscribePush", description = "Roles: MEMBER, INSTRUCTOR, ADMIN, the caller's own subscription (impersonation → "
            + "IMPERSONATION_DENIED). Logout of the device: EXPIRED + expiredAt, PushUnsubscribed; another account's or club's → 404. No body." + STUB,
            responses = @ApiResponse(responseCode = "204", description = "void", content = @Content))
    public void unsubscribePush(@PathVariable String id) {
        access.ownSubscription(id);
        throw new UnsupportedOperationException();
    }
}
