package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogSeedRepository extends TenantRepository<CatalogSeedReference> {
    public CatalogSeedRepository(MongoTemplate mongo) { super(mongo, CatalogSeedReference.class); }
}
