package com.agilityhub.core.courses.persistence;

import com.agilityhub.core.courses.domain.CourseTypes.InventoryScope;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.*;

@Repository
public class ObstacleInventoryRepository extends CourseTenantRepository<ObstacleInventory> {
    public ObstacleInventoryRepository(MongoTemplate mongo) {
        super(mongo, ObstacleInventory.class);
        mongo.indexOps(ObstacleInventory.class).createIndex(new Index().on("clubId", ASC).on("scope", ASC).on("ringId", ASC).unique().named("inventory_scope"));
    }
    private Query scope(InventoryScope scope, String ringId) {
        return tenantQuery().addCriteria(Criteria.where("scope").is(scope).and("ringId").is(ringId));
    }
    public Optional<ObstacleInventory> find(InventoryScope scope, String ringId) {
        return Optional.ofNullable(mongo.findOne(scope(scope, ringId), ObstacleInventory.class));
    }
    /**
     * R-16-12 `PUT`: one atomic upsert on the unique `{clubId, scope, ringId}` (the server retries a concurrent first insert), so two
     * saves of one scope never leave two documents; the last write wins, as S16 §6 gives an inventory no `version`.
     */
    public ObstacleInventory upsert(ObstacleInventory next) {
        tenantQuery(next.clubId());
        var update = new Update().set("items", next.items()).set("updatedAt", next.updatedAt()).set("updatedByAccountId", next.updatedByAccountId())
                .setOnInsert("_id", UUID.randomUUID().toString()).setOnInsert("createdAt", next.createdAt())
                .setOnInsert("createdByAccountId", next.createdByAccountId());
        return mongo.findAndModify(scope(next.scope(), next.ringId()), update, FindAndModifyOptions.options().upsert(true).returnNew(true), ObstacleInventory.class);
    }
}
