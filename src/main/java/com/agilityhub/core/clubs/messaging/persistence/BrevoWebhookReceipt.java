package com.agilityhub.core.clubs.messaging.persistence;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Provider receipt key is a digest of message, recipient and event; payload and credential are not persisted. */
@Document("brevo_webhook_receipts")
public record BrevoWebhookReceipt(@Id String id, String clubId, String notificationId, Instant processedAt) { }
