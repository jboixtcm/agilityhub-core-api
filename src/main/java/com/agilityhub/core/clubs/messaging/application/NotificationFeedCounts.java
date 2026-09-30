package com.agilityhub.core.clubs.messaging.application;

import com.agilityhub.core.clubs.messaging.persistence.NotificationRepository;
import org.springframework.stereotype.Service;

/**
 * The bell of screen 03 (`GET /me/home` `notifications.unreadCount`, S08 §6): the account's in-app rows of the tenant
 * not yet read — the same count feed 11 answers (`GET /me/notifications`, `read`, `read-all`: `NotificationFeedService`).
 */
@Service
public class NotificationFeedCounts {
    private final NotificationRepository notifications;
    public NotificationFeedCounts(NotificationRepository notifications) { this.notifications = notifications; }
    public int unreadApp(String accountId) { return accountId == null ? 0 : (int) notifications.unreadApp(accountId); }
}
