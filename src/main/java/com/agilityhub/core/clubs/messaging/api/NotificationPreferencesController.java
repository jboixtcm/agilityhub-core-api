package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.NotificationPreferencesService;
import com.agilityhub.core.clubs.messaging.domain.NotificationCategory;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * The «Avisos» block of 12 (the member; the impersonation token saves with audit, T-11-28) and of D10 (ADMIN: read with the
 * shape of 12, E76; saved and audited with the existing MEMBER_UPDATED, `changes[].path = notificationPreferences.…`: no
 * dedicated audit action exists). R-11-04: the
 * preferences only remove channels a template allows (the engine's `ChannelResolver` applies them).
 */
@RestController
public class NotificationPreferencesController {
    static final String TENANT = MessageTemplatesController.TENANT;
    private final NotificationPreferencesService preferences;
    public NotificationPreferencesController(NotificationPreferencesService preferences) { this.preferences = preferences; }

    @GetMapping("/api/v1/me/notification-preferences")
    @PreAuthorize("hasRole('MEMBER')")
    @AllowsImpersonation
    @Operation(summary = "myNotificationPreferences", description = "Roles: MEMBER, and the impersonation token (INSTRUCTOR, ADMIN without MEMBER → 403). "
            + "The member's NotificationPreference, the product defaults when the block is absent (OPERATIONAL e-mail off, the rest on, no reminder, push of "
            + "club news on), with the reminder options, the account's locale and the club's locales and SMS/PUSH modules." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPreferences", useReturnTypeSchema = true))
    public NotificationPreferences myNotificationPreferences() { return view(preferences.mine()); }

    @PutMapping("/api/v1/me/notification-preferences")
    @PreAuthorize("hasRole('MEMBER')")
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR, INVALID_REMINDER_OPTION})
    @Operation(summary = "saveMyNotificationPreferences", description = "Roles: MEMBER, and the impersonation token. Partial save (debounced by 12): an "
            + "absent key keeps its value; reminderMinutesBefore outside messaging.reminderOptionsMinutes → INVALID_REMINDER_OPTION (422). A change is "
            + "audited as MEMBER_UPDATED (with the impersonated member under impersonation) and publishes NotificationPreferencesChanged{memberId, diff, "
            + "byAccountId}; a save that changes nothing writes nothing. A changed reminder never resends a reminder already sent (R-11-16)." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPreferences", useReturnTypeSchema = true))
    public NotificationPreferences saveMyNotificationPreferences(@Valid @RequestBody NotificationPreferencesRequest request) {
        return view(preferences.saveMine(patch(request)));
    }

    @GetMapping("/api/v1/members/{id}/notification-preferences")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({NOT_FOUND, MEMBER_ERASED})
    @Operation(summary = "memberNotificationPreferences", description = "Roles: ADMIN (MEMBER, INSTRUCTOR → 403; impersonation → 403). The «Avisos» block "
            + "of D10 (organizer 30-09, E76): the member's NotificationPreference with exactly the shape of GET /me/notification-preferences for that member "
            + "(the product defaults when the block is absent, the reminder options, the member's account locale, the club's locales and SMS/PUSH modules). "
            + "Another club's member → 404." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPreferences", useReturnTypeSchema = true))
    public NotificationPreferences memberNotificationPreferences(@PathVariable String id) { return view(preferences.member(id)); }

    @PutMapping("/api/v1/members/{id}/notification-preferences")
    @PreAuthorize("hasRole('ADMIN') and principal.claims['imp'] != true")
    @ContractErrors({VALIDATION_ERROR, INVALID_REMINDER_OPTION, NOT_FOUND, MEMBER_ERASED})
    @Operation(summary = "saveMemberNotificationPreferences", description = "Roles: ADMIN (impersonation → 403). The same block from D10, audited as "
            + "MEMBER_UPDATED (changes[].path = notificationPreferences.…, the actor's actorAccountId) with NotificationPreferencesChanged. Another club's "
            + "member → 404." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPreferences", useReturnTypeSchema = true))
    public NotificationPreferences saveMemberNotificationPreferences(@PathVariable String id, @Valid @RequestBody NotificationPreferencesRequest request) {
        return view(preferences.saveMember(id, patch(request)));
    }

    static NotificationPreferencesService.Patch patch(NotificationPreferencesRequest request) {
        Map<NotificationCategory, Boolean> email = null;
        if (request.emailByCategory() != null) {
            var e = request.emailByCategory();
            email = new EnumMap<>(NotificationCategory.class);
            if (e.operational() != null) { email.put(NotificationCategory.OPERATIONAL, e.operational()); }
            if (e.personal() != null) { email.put(NotificationCategory.PERSONAL, e.personal()); }
            if (e.clubChanges() != null) { email.put(NotificationCategory.CLUB_CHANGES, e.clubChanges()); }
            if (e.clubNews() != null) { email.put(NotificationCategory.CLUB_NEWS, e.clubNews()); }
        }
        var reminder = request.reminderMinutesBefore();
        return new NotificationPreferencesService.Patch(email, reminder != null, reminder == null ? null : reminder.minutes(), request.pushClubNews());
    }
    static NotificationPreferences view(NotificationPreferencesService.View view) {
        var p = view.preferences();
        var email = p.emailByCategory();
        return new NotificationPreferences(new EmailByCategory(email.get(NotificationCategory.OPERATIONAL), email.get(NotificationCategory.PERSONAL),
                email.get(NotificationCategory.CLUB_CHANGES), email.get(NotificationCategory.CLUB_NEWS)), true, p.reminderMinutesBefore(),
                view.reminderOptionsMinutes(), p.pushClubNews(), view.locale(), view.availableLocales(), new PreferenceModules(view.sms(), view.push()));
    }
}
