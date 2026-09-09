package com.agilityhub.core.clubs.common.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.domain.*;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class SavedViewRepository extends TenantRepository<SavedView> {
    public SavedViewRepository(MongoTemplate mongo) {
        super(mongo, SavedView.class);
        mongo.indexOps(SavedView.class).ensureIndex(new org.springframework.data.mongodb.core.index.Index()
                .on("clubId", org.springframework.data.domain.Sort.Direction.ASC)
                .on("data.ownerAccountId", org.springframework.data.domain.Sort.Direction.ASC)
                .on("data.listKey", org.springframework.data.domain.Sort.Direction.ASC)
                .on("data.name", org.springframework.data.domain.Sort.Direction.ASC).unique());
    }
    public List<SavedView> visible(String key, String account) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("data.listKey").is(key))
                .addCriteria(new Criteria().orOperator(Criteria.where("data.ownerAccountId").is(account), Criteria.where("data.shared").is(true)))
                .with(org.springframework.data.domain.Sort.by("data.name", "_id")), SavedView.class);
    }
    public SavedView update(SavedView view, long version) {
        tenantQuery(view.clubId());
        if (mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(view.id()).and("data.version").is(version)),
                new Update().set("data", view.data()).set("updatedAt", view.updatedAt()), SavedView.class).getMatchedCount() != 1) {
            throw new ApiException(ErrorCode.STALE_VERSION);
        }
        return view;
    }
    public void delete(SavedView view) {
        if (mongo.remove(tenantQuery().addCriteria(Criteria.where("_id").is(view.id()).and("data.version").is(view.data().version())), SavedView.class).getDeletedCount() != 1) {
            throw new ApiException(ErrorCode.STALE_VERSION);
        }
    }
}
