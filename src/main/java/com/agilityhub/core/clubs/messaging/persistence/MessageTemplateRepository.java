package com.agilityhub.core.clubs.messaging.persistence;

import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.persistence.TenantRepository;
import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;

/** `message_templates` with the S11 §3 indexes: unique `{clubId, code}` over the `CATALOG` templates, and D9's `{clubId, category, status}`. */
@Repository
public class MessageTemplateRepository extends TenantRepository<MessageTemplate> {
    public MessageTemplateRepository(MongoTemplate mongo) { super(mongo, MessageTemplate.class); }
    @PostConstruct
    public void ensureIndexes() {
        var indexes = mongo.indexOps(MessageTemplate.class);
        indexes.ensureIndex(new Index().on("clubId", ASC).on("code", ASC).unique().named("template_club_code")
                .partial(PartialIndexFilter.of(Criteria.where("code").type(2))));
        indexes.ensureIndex(new Index().on("clubId", ASC).on("category", ASC).on("status", ASC).named("template_club_category_status"));
    }
    /**
     * Every club that holds a template: the one read across tenants, for E7-T06's start-up upgrade ({@code TemplateUpgrade}).
     * It runs once per start-up, outside any request, and must reach every club whose templates an earlier version stored,
     * whatever the club's status: the schedulers' `ClubConfigService.activeClubIds()` would skip a suspended club, whose
     * templates would stay outdated until it is reactivated. It returns club ids only; the upgrade then reads and writes
     * each club's templates through the tenant-scoped methods, one tenant at a time.
     */
    public List<String> clubIds() {
        return mongo.findDistinct(new Query(), "clubId", MessageTemplate.class, String.class).stream().filter(Objects::nonNull).sorted().toList();
    }
    /** The club's template of a catalog code (R-11-01). */
    public Optional<MessageTemplate> findByCode(String code) {
        return Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("code").is(code)), MessageTemplate.class));
    }
    /**
     * D9's save (R-11-12) with optimistic locking: the stored template of the tenant at `expectedVersion` becomes `next` at
     * `expectedVersion + 1` in one conditional update (`null` fields removed); another version → `409 STALE_VERSION`.
     */
    public MessageTemplate update(MessageTemplate next, long expectedVersion) {
        var query = tenantQuery(next.clubId()).addCriteria(Criteria.where("_id").is(next.id()).and("version").is(expectedVersion));
        var document = new Document();
        mongo.getConverter().write(next, document);
        var update = new Update();
        document.forEach((key, value) -> { if (!key.equals("_id") && !key.equals("_class") && !key.equals("version")) { update.set(key, value); } });
        for (String nullable : List.of("smsBody", "updatedBy")) { if (!document.containsKey(nullable)) { update.unset(nullable); } }
        update.set("version", expectedVersion + 1);
        if (mongo.updateFirst(query, update, MessageTemplate.class).getMatchedCount() != 1) { throw new ApiException(ErrorCode.STALE_VERSION); }
        return new MessageTemplate(next.id(), next.clubId(), next.code(), next.kind(), next.category(), next.title(), next.body(), next.smsBody(), next.icon(),
                next.color(), next.matrix(), next.enabled(), next.mandatory(), next.customized(), next.status(), expectedVersion + 1, next.createdAt(),
                next.createdBy(), next.updatedAt(), next.updatedBy());
    }
}
