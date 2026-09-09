package com.agilityhub.core.clubs.followup.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.domain.*;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.*;
import org.springframework.stereotype.Repository;

@Repository
public class UploadGrantRepository extends TenantRepository<UploadGrant> {
    public UploadGrantRepository(MongoTemplate mongo) { super(mongo, UploadGrant.class); }
    public void bind(String key, String entity) {
        var query = tenantQuery().addCriteria(Criteria.where("_id").is(key)).addCriteria(new Criteria().orOperator(
                Criteria.where("boundEntity").is(null), Criteria.where("boundEntity").is(entity)));
        if (mongo.updateFirst(query, new Update().set("boundEntity", entity), UploadGrant.class).getMatchedCount() != 1) {
            throw new ApiException(ErrorCode.ATTACHMENT_ENTITY_MISMATCH);
        }
    }
}
