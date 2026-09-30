package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.application.ports.MemberDirectoryPort;
import com.agilityhub.core.clubs.messaging.application.ports.WaitlistRelevancePort;
import com.agilityhub.core.clubs.messaging.domain.NotificationActionType;
import com.agilityhub.core.clubs.messaging.domain.NotificationAudience;
import com.agilityhub.core.clubs.messaging.domain.NotificationChannel;
import com.agilityhub.core.clubs.messaging.persistence.Notification;
import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Feed 11 (S11 R-11-10, R-11-11) of the caller's account — the impersonated member's under impersonation: the notices of any
 * audience with a delivered `APP` delivery, `createdAt desc`; `channels` = APP, plus SMS and PUSH when a delivery of theirs
 * reached the provider («i per SMS»; the e-mail is never shown, part C S11); the native action with `enabled` computed on
 * reading (`CLAIM_SEAT`: the waiting-list entry is still `NOTIFIED` and, in FIFO, before `confirmBy`; `CHANGE_CLASS`: the dog
 * is still accessible; every other action opens its screen). `read` and `read-all` are idempotent and answer the same
 * `unreadCount` as `GET /me/home` (S08, {@link NotificationFeedCounts}).
 */
@Service
public class NotificationFeedService {
    public static final int MAX_SIZE = 100;
    private final NotificationRepository notifications; private final MessagingContractAccess access; private final WaitlistRelevancePort waitlist;
    private final MemberDirectoryPort members; private final Clock clock;

    public NotificationFeedService(NotificationRepository notifications, MessagingContractAccess access, WaitlistRelevancePort waitlist, MemberDirectoryPort members,
            Clock clock) {
        this.notifications = notifications; this.access = access; this.waitlist = waitlist; this.members = members; this.clock = clock;
    }

    public record Action(NotificationActionType type, Map<String, String> params, boolean enabled) { }
    public record Item(Notification notification, List<NotificationChannel> channels, Action action) { }
    public record Page(List<Item> items, int page, int size, long totalItems, long unreadCount) { }

    public Page feed(int page, int size, NotificationAudience audience) {
        if (page < 0 || size < 1 || size > MAX_SIZE) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR, Map.of("fieldErrors", List.of(Map.of("field", page < 0 ? "page" : "size", "code", "INVALID_VALUE"))));
        }
        String account = access.account();
        var now = clock.instant();
        var items = notifications.feed(account, audience, page, size).stream().map(n -> new Item(n, channels(n), action(n, account, now))).toList();
        return new Page(items, page, size, notifications.feedCount(account, audience), notifications.unreadApp(account));
    }

    /** Marks one of the caller's notices read; another account's or club's is 404. */
    public long read(String id) {
        String account = access.account();
        access.ownNotification(id);
        notifications.markRead(id, account, clock.instant());
        return notifications.unreadApp(account);
    }

    /** Entering screen 11 (part C S11): every notice of the caller created until now is read. */
    public long readAll() {
        String account = access.account();
        var now = clock.instant();
        notifications.markAllRead(account, now, now);
        return notifications.unreadApp(account);
    }

    /** APP, then SMS and PUSH when one of their deliveries reached the provider; never EMAIL. */
    static List<NotificationChannel> channels(Notification notification) {
        var channels = new ArrayList<NotificationChannel>();
        channels.add(NotificationChannel.APP);
        for (var channel : List.of(NotificationChannel.SMS, NotificationChannel.PUSH)) {
            boolean reached = notification.deliveries() != null && notification.deliveries().stream()
                    .anyMatch(d -> d.channel() == channel && d.status() != null && d.status().reached());
            if (reached) { channels.add(channel); }
        }
        return channels;
    }

    private Action action(Notification notification, String account, java.time.Instant now) {
        var action = notification.action();
        if (action == null || action.type() == null) { return null; }
        var params = action.params() == null ? Map.<String, String>of() : action.params();
        boolean enabled = switch (action.type()) {
            case CLAIM_SEAT -> params.get("waitlistEntryId") != null && waitlist.stillNotified(params.get("waitlistEntryId"), now);
            // The member's button books another class for the dog; the staff copies (N-16 to the admins, N-17) open the class.
            case CHANGE_CLASS -> notification.audience() != NotificationAudience.MEMBER
                    || params.get("dogId") != null && members.dogAccessible(account, params.get("dogId"));
            default -> true;
        };
        return new Action(action.type(), params, enabled);
    }
}
