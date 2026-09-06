package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemberIdentityRepository extends TenantRepository<MemberIdentity> {
    public MemberIdentityRepository(MongoTemplate mongo) { super(mongo, MemberIdentity.class); }
}
