package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;

public interface CatalogEntity extends TenantEntity {
    int order();
    boolean active();
    long version();
    Instant createdAt();
    Instant updatedAt();
    String createdByAccountId();
    String updatedByAccountId();
}
