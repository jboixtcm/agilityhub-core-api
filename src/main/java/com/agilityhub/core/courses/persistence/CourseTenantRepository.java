package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;

/** S16 documents are retained; behavior tasks implement their soft-delete transitions. */
public abstract class CourseTenantRepository<T extends TenantEntity> extends TenantRepository<T> {
    protected CourseTenantRepository(MongoTemplate mongo, Class<T> type) { super(mongo, type); }
    @Override public final boolean deleteById(String id) { throw new UnsupportedOperationException("S16 retains its documents"); }
}
