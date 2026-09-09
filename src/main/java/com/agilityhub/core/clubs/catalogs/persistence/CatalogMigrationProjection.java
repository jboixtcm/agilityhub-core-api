package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class CatalogMigrationProjection extends TenantRepository<Level> {
    public CatalogMigrationProjection(MongoTemplate mongo) { super(mongo,Level.class); }
    public List<Map<String,Object>> rows(String collection) {
        if (!Set.of("levels","plans","prices").contains(collection)) { throw new IllegalArgumentException("Unsupported catalog"); }
        return mongo.find(tenantQuery(),Document.class,collection).stream().map(d -> (Map<String,Object>) d).toList();
    }
    public void lock() {
        for (String type:List.of("Level","Plan")) {
            mongo.upsert(tenantQuery().addCriteria(Criteria.where("_id").is(TenantContext.require()+":"+type)),new Update().inc("sequence",1),"catalog_write_locks");
        }
    }
}
