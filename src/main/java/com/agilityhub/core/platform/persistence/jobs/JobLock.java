package com.agilityhub.core.platform.persistence.jobs;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** S15 R-15-06 lease: `_id` is `{clubId}:{job}` or `tick`; the TTL index removes abandoned leases. */
@Document("job_locks")
public record JobLock(@Id String id, String holder, Instant acquiredAt, Instant expiresAt) { }
