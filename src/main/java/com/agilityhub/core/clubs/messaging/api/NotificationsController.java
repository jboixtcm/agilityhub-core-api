package com.agilityhub.core.clubs.messaging.api;

import com.agilityhub.core.clubs.messaging.application.MessagingContractAccess;
import com.agilityhub.core.clubs.messaging.application.NotificationFeedService;
import com.agilityhub.core.clubs.messaging.application.NotificationLog;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.shared.application.contract.AllowsImpersonation;
import com.agilityhub.core.shared.application.contract.ApiContracts.FilterValues;
import com.agilityhub.core.shared.application.contract.ApiContracts.ListPage;
import com.agilityhub.core.shared.application.contract.ContractErrors;
import com.agilityhub.core.shared.application.contract.ListContract;
import com.agilityhub.core.shared.application.lists.ListEngine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import static com.agilityhub.core.clubs.messaging.api.MessagingContracts.*;
import static com.agilityhub.core.shared.domain.ErrorCode.*;

/**
 * S11 §6 notification log (ADMIN, R-11-10) and feed 11 (MEMBER, INSTRUCTOR, ADMIN; the impersonation token reads and marks
 * the member's feed, T-11-28), served since E7-T03.
 */
@RestController
public class NotificationsController {
    static final String ADMIN = "hasRole('ADMIN') and principal.claims['imp'] != true";
    static final String FEED = "hasAnyRole('MEMBER','INSTRUCTOR','ADMIN')";
    static final String TENANT = MessageTemplatesController.TENANT;
    private final MessagingContractAccess access; private final NotificationLog log; private final NotificationFeedService feed; private final ListEngine lists;

    public NotificationsController(MessagingContractAccess access, NotificationLog log, NotificationFeedService feed, ListEngine lists) {
        this.access = access; this.log = log; this.feed = feed; this.lists = lists;
    }

    @GetMapping("/api/v1/notifications")
    @PreAuthorize(ADMIN)
    @ListContract(filterable = {"code", "category", "channel", "status", "memberId", "createdAt"}, sortable = {"createdAt"},
            columns = {"createdAt*", "code*", "recipient*", "channels*", "readAt"}, paged = true, exportable = true,
            fields = {"id", "createdAt", "code", "category", "audience", "recipient", "channels", "readAt"})
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "notifications", description = "Roles: ADMIN (MEMBER, INSTRUCTOR → 403; impersonation → 403). The club's notification log "
            + "(R-11-10, T-11-26), universal list (CONVENCIONS_API §4) createdAt desc by default: every notification, also those with no external channel "
            + "sent and those without an APP delivery; never another club's. channel/status filter on any delivery; memberId on the recipient. An "
            + "undeclared filter, sort or fields key is 400 INVALID_FILTER." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "NotificationPage",
                    content = @Content(mediaType = "application/json", schema = @Schema(implementation = NotificationPage.class))))
    public ListPage<Map<String, Object>> notifications(@Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        return log.list(lists, params);
    }

    @GetMapping("/api/v1/notifications/filter-values")
    @PreAuthorize(ADMIN)
    @ListContract(filterable = {"code", "category", "channel", "status", "memberId", "createdAt"}, sortable = {}, columns = {}, paged = false, exportable = false)
    @ContractErrors({INVALID_FILTER})
    @Operation(summary = "notificationFilterValues", description = "Roles: ADMIN (impersonation → 403). The values of one x-filterable field with their "
            + "counts under the other filters (CONVENCIONS_API §4), labelled in the reader's language (the member's name for memberId); another field → "
            + "INVALID_FILTER." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "FilterValues", useReturnTypeSchema = true))
    public FilterValues notificationFilterValues(@RequestParam String field, @RequestParam(required = false) String q, @RequestParam(required = false) List<String> filter,
            @Parameter(hidden = true) @RequestParam MultiValueMap<String, String> params) {
        access.tenant();
        access.filterValues(field, params);
        var query = new org.springframework.util.LinkedMultiValueMap<String, String>();
        if (params.containsKey("filter")) { query.put("filter", params.get("filter")); }
        if (params.containsKey("q")) { query.put("q", params.get("q")); }
        return log.filterValues(lists, field, query);
    }

    @GetMapping("/api/v1/notifications/{id}")
    @PreAuthorize(ADMIN)
    @ContractErrors({NOT_FOUND})
    @Operation(summary = "notification", description = "Roles: ADMIN (impersonation → 403). The log's detail: the frozen rendered texts, every delivery "
            + "with the provider's reference and last error. Another club's → 404." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "NotificationDetail", useReturnTypeSchema = true))
    public NotificationDetail notification(@PathVariable String id) { return detail(access.notification(id)); }

    @GetMapping("/api/v1/me/notifications")
    @PreAuthorize(FEED)
    @AllowsImpersonation
    @ContractErrors({VALIDATION_ERROR})
    @Operation(summary = "meNotifications", description = "Roles: MEMBER, INSTRUCTOR, ADMIN, and the impersonation token (the member's feed). Feed 11 "
            + "(R-11-10): the caller's notifications with an APP delivery, of any audience (audience filters one), createdAt desc, 20 per page (size ≤ 100); "
            + "channels lists APP, and SMS or PUSH only when one of their deliveries is SENT or DELIVERED («i per SMS»; the e-mail is never listed); "
            + "action.enabled is computed on reading (CLAIM_SEAT: the entry is still NOTIFIED and, in FIFO, confirmBy > now; CHANGE_CLASS: the member "
            + "may still book for the dog); unreadCount = GET /me/home notifications.unreadCount." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "MeNotifications", useReturnTypeSchema = true))
    public MeNotifications meNotifications(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) NotificationAudience audience) {
        var result = feed.feed(page, size, audience);
        return new MeNotifications(result.items().stream().map(NotificationsController::item).toList(), result.page(), result.size(), result.totalItems(),
                result.unreadCount());
    }

    @PostMapping("/api/v1/me/notifications/{id}/read")
    @PreAuthorize(FEED)
    @AllowsImpersonation
    @ContractErrors({NOT_FOUND})
    @Operation(summary = "readNotification", description = "Roles: MEMBER, INSTRUCTOR, ADMIN, and the impersonation token. Marks one of the caller's "
            + "notifications read (idempotent: readAt kept); another account's or club's → 404. Answers the new unreadCount." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "ReadResult", useReturnTypeSchema = true))
    public ReadResult readNotification(@PathVariable String id) { return new ReadResult(feed.read(id)); }

    @PostMapping("/api/v1/me/notifications/read-all")
    @PreAuthorize(FEED)
    @AllowsImpersonation
    @Operation(summary = "readAllNotifications", description = "Roles: MEMBER, INSTRUCTOR, ADMIN, and the impersonation token. Entering screen 11 marks "
            + "every notification of the caller created until now read (DECISIONS_PENDENTS part C S11; idempotent); the bell of 03 goes out." + TENANT,
            responses = @ApiResponse(responseCode = "200", description = "ReadResult", useReturnTypeSchema = true))
    public ReadResult readAllNotifications() { return new ReadResult(feed.readAll()); }

    // ---- mapping

    private static MeNotification item(NotificationFeedService.Item item) {
        var n = item.notification();
        var action = item.action() == null ? null : new FeedAction(item.action().type(), item.action().params(), item.action().enabled());
        return new MeNotification(n.id(), n.code(), n.category(), n.icon(), n.color(), Objects.toString(n.title(), ""), Objects.toString(n.body(), ""), n.createdAt(),
                item.channels(), n.readAt(), action);
    }
    static NotificationDetail detail(Notification n) {
        var recipient = n.recipient() == null ? new NotificationRecipient("", null, null)
                : new NotificationRecipient(Objects.toString(n.recipient().displayName(), ""), n.recipient().memberId(), n.recipient().email());
        var deliveries = n.deliveries() == null ? List.<Notification.Delivery>of() : n.deliveries();
        var s = n.subject();
        var subject = s == null ? new NotificationSubject(null, null, null, null, null, null, null, null, null)
                : new NotificationSubject(s.dogId(), s.bookingId(), s.classSessionId(), s.waitlistEntryId(), s.trainingBookingId(), s.invoiceId(), s.activityId(), s.taskId(),
                        s.memberId());
        return new NotificationDetail(n.id(), n.createdAt(), n.code(), n.category(), n.audience(), recipient,
                deliveries.stream().map(d -> new ChannelState(d.channel(), d.status())).toList(), n.readAt(), Objects.toString(n.title(), ""),
                Objects.toString(n.body(), ""), n.smsBody(), Objects.toString(n.locale(), ""), n.templateId(), n.templateVersion(), n.eventType(), subject,
                deliveries.stream().map(d -> new DeliveryView(d.channel(), d.target(), d.status(), d.attempts(), d.nextAttemptAt(), d.providerRef(), d.lastError(),
                        d.sentAt(), d.deliveredAt(), d.failedAt())).toList());
    }
}
