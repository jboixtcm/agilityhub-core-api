package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.MessagingContractAccess;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterValues;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S11 §6 notification log (ADMIN) and feed 11 (MEMBER, INSTRUCTOR, ADMIN; the impersonation token reads and marks the
 * member's feed, T-11-28). Every operation runs its guards and answers 501 NOT_IMPLEMENTED until E7-T03.
 */
@RestController
public class NotificationsController {
    static final String STUB = MessageTemplatesController.STUB;
    static final String ADMIN = "hasRole('ADMIN') and principal.claims['imp'] != true";
    static final String FEED = "hasAnyRole('MEMBER','INSTRUCTOR','ADMIN')";
    private final MessagingContractAccess access;
    public NotificationsController(MessagingContractAccess access) { this.access = access; }

    @GetMapping("/api/v1/notifications")
    @PreAuthorize(ADMIN)
    @ListContract(filterable = {"code", "category", "channel", "status", "memberId", "createdAt"}, sortable = {"createdAt"},
            columns = {"createdAt*", "code*", "recipient*", "channels*", "readAt"}, paged = true, exportable = true,
            fields = {"id", "createdAt", "code", "category", "audience", "recipient", "channels", "readAt"})
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "notifications", description = "Roles: ADMIN (MEMBER, INSTRUCTOR → 403; impersonation → 403). The club's notification log "
            + "(R-11-10, T-11-26), universal list (CONVENCIONS_API §4) createdAt desc by default: every notification, also those with no external channel "
            + "sent and those without an APP delivery; never another club's. channel/status filter on any delivery; memberId on the recipient. An "
            + "undeclared filter, sort or fields key is 400 INVALID_FILTER." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = NotificationPage.class))))
    public Object notifications(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.notificationList(params);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/notifications/filter-values")
    @PreAuthorize(ADMIN)
    @ListContract(filterable = {"code", "category", "channel", "status", "memberId", "createdAt"}, sortable = {}, columns = {}, paged = false, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "notificationFilterValues", description = "Roles: ADMIN (impersonation → 403). The values of one x-filterable field with their "
            + "counts under the other filters (CONVENCIONS_API §4); another field → INVALID_FILTER." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "FilterValues", useReturnTypeSchema = true))
    public FilterValues notificationFilterValues(@RequestParam String field, @RequestParam(required = false) String q, @RequestParam(required = false) List<String> filter,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.filterValues(field, params);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/notifications/{id}")
    @PreAuthorize(ADMIN)
    @ContractErrors({NOT_FOUND})
    @Operation(summary = "notification", description = "Roles: ADMIN (impersonation → 403). The log's detail: the frozen rendered texts, every delivery "
            + "with the provider's reference and last error. Another club's → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "NotificationDetail", useReturnTypeSchema = true))
    public NotificationDetail notification(@PathVariable String id) {
        access.notification(id);
        throw new UnsupportedOperationException();
    }

    @GetMapping("/api/v1/me/notifications")
    @PreAuthorize(FEED)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "meNotifications", description = "Roles: MEMBER, INSTRUCTOR, ADMIN, and the impersonation token (the member's feed). Feed 11 "
            + "(R-11-10): the caller's notifications with an APP delivery, of any audience (audience filters one), createdAt desc, 20 per page; channels "
            + "lists SMS only when an SMS delivery is SENT or DELIVERED («i per SMS»); action.enabled is computed on reading (CLAIM_SEAT: the entry is "
            + "still NOTIFIED and, in FIFO, confirmBy > now); unreadCount = GET /me/home notifications.unreadCount." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "MeNotifications", useReturnTypeSchema = true))
    public MeNotifications meNotifications(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) NotificationAudience audience) {
        access.tenant();
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/me/notifications/{id}/read")
    @PreAuthorize(FEED)
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND})
    @Operation(summary = "readNotification", description = "Roles: MEMBER, INSTRUCTOR, ADMIN, and the impersonation token. Marks one of the caller's "
            + "notifications read (idempotent: readAt kept); another account's or club's → 404." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "ReadResult", useReturnTypeSchema = true))
    public ReadResult readNotification(@PathVariable String id) {
        access.ownNotification(id);
        throw new UnsupportedOperationException();
    }

    @PostMapping("/api/v1/me/notifications/read-all")
    @PreAuthorize(FEED)
    @AllowsImpersonation
    @Operation(summary = "readAllNotifications", description = "Roles: MEMBER, INSTRUCTOR, ADMIN, and the impersonation token. Entering screen 11 marks "
            + "every notification of the caller created until now read (DECISIONS_PENDENTS part C S11; idempotent); the bell of 03 goes out." + STUB,
            responses = @ApiResponse(responseCode = "200", description = "ReadResult", useReturnTypeSchema = true))
    public ReadResult readAllNotifications() {
        access.tenant();
        throw new UnsupportedOperationException();
    }
}
