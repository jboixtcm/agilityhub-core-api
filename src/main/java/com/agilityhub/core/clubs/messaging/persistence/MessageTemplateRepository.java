package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.Optional;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** `message_templates` with the S11 §3 indexes: unique `{clubId, code}` over the `CATALOG` templates, and D9's `{clubId, category, status}`. */
@Repository
public class MessageTemplateRepository extends TenantRepository<MessageTemplate> {
    public MessageTemplateRepository(MongoTemplate mongo) { super(mongo, MessageTemplate.class); }
    @jakarta.annotation.PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(MessageTemplate.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("code", ASC).unique().named("template_club_code")
                .partial(PartialIndexFilter.of(Criteria.where("code").type(2))));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("category", ASC).on("status", ASC).named("template_club_category_status"));
    }
    /** The club's template of a catalog code (R-11-01). */
    public Optional<MessageTemplate> findByCode(String code) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("code").is(code)), MessageTemplate.class));
    }
}
