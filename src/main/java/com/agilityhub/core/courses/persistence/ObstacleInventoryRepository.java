package com.agilityhub.core.courses.persistence;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class ObstacleInventoryRepository extends CourseTenantRepository<ObstacleInventory> {
    public ObstacleInventoryRepository(MongoTemplate mongo) {
        super(mongo, ObstacleInventory.class);
        mongo.indexOps(ObstacleInventory.class).ensureIndex(new Index().on("clubId", ASC).on("scope", ASC).on("ringId", ASC).unique().named("inventory_scope"));
    }
}
