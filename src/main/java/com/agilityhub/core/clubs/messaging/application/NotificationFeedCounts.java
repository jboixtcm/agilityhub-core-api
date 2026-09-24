package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import org.springframework.stereotype.Service;

/**
 * The bell of screen 03 (`GET /me/home` `notifications.unreadCount`, S08 §6): the account's in-app rows of the tenant
 * not yet read. Rows carry no `readAt` until the S11 inbox (E7) marks them, so every in-app row counts until then.
 */
@Service
public class NotificationFeedCounts {
    private final NotificationRepository notifications;
    public NotificationFeedCounts(NotificationRepository notifications) { this.notifications = notifications; }
    public int unreadApp(String accountId) { return accountId == null ? 0 : (int) notifications.unreadApp(accountId); }
}
