package com.agilityhub.core.clubs.messaging.persistence;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Brevo's account-wide permanent suppression. Address digest is pseudonymous, not anonymous. No automatic unblocking. */
@Document("brevo_email_suppressions")
public record BrevoEmailSuppression(@Id String id, String reason, Instant createdAt) { }
