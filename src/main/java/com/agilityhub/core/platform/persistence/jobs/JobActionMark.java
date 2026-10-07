package com.agilityhub.core.platform.persistence.jobs;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A notification-only job item's durable mark, committed together with its outbox event. */
@Document("job_action_marks")
public record JobActionMark(@Id String id, String clubId, String action, String period, Instant createdAt) implements TenantEntity { }
