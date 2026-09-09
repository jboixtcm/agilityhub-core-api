package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;

/** Mongo aggregate identity; HTTP uses explicit projections. */
public abstract class CensusEntity implements TenantEntity {
    @Id public String id;
    public String clubId;
    @Version public Long version;
    public Instant createdAt;
    public Instant updatedAt;
    @Override public String id() { return id; }
    @Override public String clubId() { return clubId; }
    public long version() { return version == null ? 0 : version; }
}
