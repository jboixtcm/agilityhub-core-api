package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.MessagingContractAccess;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * The «Avisos» block of 12 (the member; the impersonation token saves with audit, T-11-28) and of D10 (ADMIN, audited with
 * the existing MEMBER_UPDATED, `changes[].path = notificationPreferences`: no dedicated audit action exists). R-11-04: the
 * preferences only remove channels a template allows. 501 NOT_IMPLEMENTED after the guards until E7-T03.
 */
@RestController
public class NotificationPreferencesController {
    static final String STUB = MessageTemplatesController.STUB;
    private final MessagingContractAccess access;
    public NotificationPreferencesController(MessagingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/me/notification-preferences")
    @PreAuthorize("hasRole('MEMBER')")
    @AllowsImpersonation
    @Operation(summary = "myNotificationPreferences", description = "Roles: MEMBER, and the impersonation token (INSTRUCTOR, ADMIN without MEMBER → 403). "
            + "The member's NotificationPreference, the product defaults when the block is absent (OPERATIONAL e-mail off, the rest on, no reminder, push of "
            + "club news on), with the reminder options, the account's locale and the club's locales and SMS/PUSH modules." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPreferences", useReturnTypeSchema = true))
    public NotificationPreferences myNotificationPreferences() {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PutMapping("/api/v1/me/notification-preferences")
    @PreAuthorize("hasRole('MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, INVALID_REMINDER_OPTION})
    @Operation(summary = "saveMyNotificationPreferences", description = "Roles: MEMBER, and the impersonation token (audited as MEMBER_UPDATED with the "
            + "impersonated member). Partial save (debounced by 12): an absent key keeps its value; reminderMinutesBefore outside "
            + "messaging.reminderOptionsMinutes → INVALID_REMINDER_OPTION (422). NotificationPreferencesChanged{memberId, diff, byAccountId}; a changed "
            + "reminder never resends a reminder already sent (R-11-16)." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPreferences", useReturnTypeSchema = true))
    public NotificationPreferences saveMyNotificationPreferences(@Valid @RequestBody NotificationPreferencesRequest request) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PutMapping("/api/v1/members/{id}/notification-preferences")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, INVALID_REMINDER_OPTION, NOT_FOUND, MEMBER_ERASED})
    @Operation(summary = "saveMemberNotificationPreferences", description = "Roles: ADMIN (impersonation → 403). The same block from D10, audited as "
            + "MEMBER_UPDATED (changes[].path = notificationPreferences, the actor's actorAccountId). Another club's member → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPreferences", useReturnTypeSchema = true))
    public NotificationPreferences saveMemberNotificationPreferences(@PathVariable String id, @Valid @RequestBody NotificationPreferencesRequest request) {
        access.member(id);
        throw new UnsupportedOperationException();
    }
}
