package com.agilityhub.core.clubs.statistics.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import java.util.List;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.FindAndReplaceOptions;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Repository;

@Repository
public class CompetitionImportRepository extends TenantRepository<CompetitionImportRecord> {
    public CompetitionImportRepository(MongoTemplate mongo) { super(mongo, CompetitionImportRecord.class); }
    @jakarta.annotation.PostConstruct
    public void indexes() {
        mongo.indexOps(CompetitionImportRecord.class).createIndex(new Index().on("clubId", Sort.Direction.ASC)
                .on("entries.dogId", Sort.Direction.ASC).on("season", Sort.Direction.ASC));
    }
    public List<CompetitionImportRecord> forDog(String dogId) {
        return mongo.find(tenantQuery().addCriteria(Criteria.where("entries.dogId").is(dogId)), CompetitionImportRecord.class);
    }
    public List<java.util.Map<String, Object>> exportDogs(java.util.Collection<String> dogIds) {
        if (dogIds.isEmpty()) { return List.of(); }
        var rows = mongo.find(tenantQuery().addCriteria(Criteria.where("entries.dogId").in(dogIds)), CompetitionImportRecord.class);
        return rows.stream().map(record -> {
            var own = new CompetitionImportRecord(record.id(), record.clubId(), record.source(), record.sourceUrl(), record.season(),
                    record.importedAt(), record.revision(), record.entries().stream().filter(entry -> dogIds.contains(entry.dogId())).toList(), record.organizingClubName());
            var document = new org.bson.Document(); mongo.getConverter().write(own, document);
            return com.agilityhub.core.platform.application.privacy.MemberDataPackage.row(document);
        }).toList();
    }
    public long eraseDogs(java.util.Collection<String> dogIds) {
        if (dogIds.isEmpty()) { return 0; }
        return mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("entries.dogId").in(dogIds)),
                new org.springframework.data.mongodb.core.query.Update().pull("entries", new org.bson.Document("dogId", new org.bson.Document("$in", dogIds)))
                        .inc("revision", 1), CompetitionImportRecord.class).getModifiedCount();
    }
    public CompetitionImportRecord save(CompetitionImportRecord record, long expected) {
        try {
            if (expected == 0) { return insert(record); }
            var query = tenantQuery(record.clubId()).addCriteria(Criteria.where("_id").is(record.id()).and("revision").is(expected));
            var saved = mongo.findAndReplace(query, record, FindAndReplaceOptions.options().returnNew());
            if (saved == null) { throw new ApiException(ErrorCode.STALE_VERSION); }
            return saved;
        } catch (org.springframework.dao.DuplicateKeyException conflict) { throw new ApiException(ErrorCode.STALE_VERSION); }
    }
}
