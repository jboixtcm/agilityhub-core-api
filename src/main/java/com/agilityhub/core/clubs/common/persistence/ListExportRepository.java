package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ListExportRepository extends TenantRepository<QueuedListExport> {
    public ListExportRepository(MongoTemplate mongo) { super(mongo, QueuedListExport.class); }
}
