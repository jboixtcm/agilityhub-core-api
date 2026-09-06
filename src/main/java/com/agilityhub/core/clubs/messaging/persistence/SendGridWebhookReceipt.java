package com.agilityhub.core.clubs.messaging.persistence;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Provider event ids are global; this infrastructure record contains no request payload. */
@Document("sendgrid_webhook_receipts")
public record SendGridWebhookReceipt(@Id String id, String clubId, String notificationId, Instant processedAt) { }
