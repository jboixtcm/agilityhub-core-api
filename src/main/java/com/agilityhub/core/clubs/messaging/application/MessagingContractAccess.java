package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.MessageTemplate;
import com.agilityhub.core.clubs.messaging.persistence.MessageTemplateRepository;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscription;
import com.agilityhub.core.clubs.messaging.persistence.PushSubscriptionRepository;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.MemberIdentityAccess;
import com.agilityhub.core.shared.application.TeamMemberAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListDefinition;
import com.agilityhub.core.shared.application.lists.ListDefinition.Field;
import com.agilityhub.core.shared.application.lists.ListDefinition.Type;
import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;

/**
 * Read-only tenant, ownership and list guards of the reserved S11 operations (E7-T01); no side effects. E7-T03 serves the
 * operations behind them. What a caller may not see — another club's template, notification or member, another account's
 * notification or push subscription — is 404, never 403 (T-11-29).
 */
@Service
public class MessagingContractAccess {
    /** `GET /notifications` (S11 §6, R-11-10): the universal list allowlist of CONVENCIONS_API §4, also the export's (S14 R-14-12). */
    public static final ListDefinition NOTIFICATIONS = new ListDefinition("notifications",
            Map.of("code", new Field("code", Type.TEXT), "category", new Field("category", Type.TEXT), "channel", new Field("deliveries.channel", Type.TEXT),
                    "status", new Field("deliveries.status", Type.TEXT), "memberId", new Field("recipient.memberId", Type.TEXT),
                    "createdAt", new Field("createdAt", Type.INSTANT)),
            Map.of("createdAt", "createdAt"), List.of(),
            List.of("createdAt", "code", "recipient", "channels", "readAt"), List.of("createdAt", "code", "recipient", "channels"),
            List.of("createdAt,desc"), Set.of("id", "createdAt", "code", "category", "audience", "recipient", "channels", "readAt"));

    private final MessageTemplateRepository templates; private final NotificationRepository notifications; private final PushSubscriptionRepository subscriptions;
    private final TeamMemberAccess members; private final MemberIdentityAccess identities;
    public MessagingContractAccess(MessageTemplateRepository templates, NotificationRepository notifications, PushSubscriptionRepository subscriptions,
            TeamMemberAccess members, MemberIdentityAccess identities) {
        this.templates = templates; this.notifications = notifications; this.subscriptions = subscriptions; this.members = members; this.identities = identities;
    }

    public void tenant() { TenantContext.require(); }
    public MessageTemplate template(String id) { return templates.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    public Notification notification(String id) { return notifications.findById(id).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND)); }
    /** A notification of the caller's feed: its account's (the impersonated member's account under impersonation). */
    public Notification ownNotification(String id) {
        return notifications.findForAccount(id, account()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    public PushSubscription ownSubscription(String id) {
        return subscriptions.findOwn(id, account()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    /** A member of the club for D10 (`NOT_FOUND` for another club's; an erased one is the census's `MEMBER_ERASED`). */
    public void member(String id) { members.teamMember(id); }
    /** The query of the log and of its export: an undeclared filter, sort, size or `fields` key is `INVALID_FILTER`. */
    public ListQuery notificationList(MultiValueMap<String, String> params) { return ListQuery.parse(NOTIFICATIONS, params); }
    /** `filter-values`: a declared field only (its own filters are those of the list). */
    public void filterValues(String field, MultiValueMap<String, String> params) {
        NOTIFICATIONS.field(field);
        var others = new org.springframework.util.LinkedMultiValueMap<String, String>();
        if (params.containsKey("filter")) { others.put("filter", params.get("filter")); }
        if (params.containsKey("q")) { others.put("q", params.get("q")); }
        ListQuery.parse(NOTIFICATIONS, others);
    }
    /** The account whose feed and devices the caller manages: its own, or the impersonated member's (MATRIU rule 3). */
    String account() {
        var user = CurrentUser.current();
        if (user == null) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        return user.impersonation() == null ? user.accountId() : identities.impersonationAccount(user.impersonation().memberId());
    }
}
