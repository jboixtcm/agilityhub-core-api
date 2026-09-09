package com.agilityhub.core.clubs.catalogs.domain;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;

public interface OfferEntity extends TenantEntity {
    long version();
    Instant createdAt();
    Instant updatedAt();
    String createdByAccountId();
    String updatedByAccountId();
}
