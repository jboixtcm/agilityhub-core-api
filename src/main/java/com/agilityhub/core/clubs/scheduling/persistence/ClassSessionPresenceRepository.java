package com.agilityhub.core.clubs.scheduling.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.stereotype.Repository;

/** Minimal read projection until the scheduling aggregate is implemented. */
@Repository
public class ClassSessionPresenceRepository extends TenantRepository<ClassSessionPresenceRepository.Presence> {
    public ClassSessionPresenceRepository(MongoTemplate mongo) { super(mongo, Presence.class); }
    public boolean hasClasses() { return mongo.exists(tenantQuery(), Presence.class); }
    @Document("class_sessions")
    public record Presence(@Id String id, String clubId) implements TenantEntity { }
}
