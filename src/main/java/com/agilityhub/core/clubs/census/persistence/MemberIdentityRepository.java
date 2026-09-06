package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MemberIdentityRepository extends TenantRepository<MemberIdentity> {
    public MemberIdentityRepository(MongoTemplate mongo) { super(mongo, MemberIdentity.class); }
    public java.util.Optional<MemberIdentity> forAccount(String memberId, String accountId) {
        return java.util.Optional.ofNullable(mongo.findOne(tenantQuery()
                .addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(memberId)
                        .and("accountId").is(accountId).and("status").ne("ERASED")), MemberIdentity.class));
    }
    public void updateOnboarding(MemberIdentity member, String phone, Boolean imageConsent, String version, java.time.Instant at) {
        var update = new org.springframework.data.mongodb.core.query.Update();
        if (phone != null) {
            var phones = new java.util.ArrayList<>(member.phones() == null ? java.util.List.<MemberIdentity.Phone>of() : member.phones());
            // The onboarding wire field is a single full number. Keep other contacts and the primary label.
            var primary = new MemberIdentity.Phone("", phone, phones.isEmpty() ? null : phones.getFirst().label());
            if (phones.isEmpty()) { phones.add(primary); } else { phones.set(0, primary); }
            update.set("phones", phones);
        }
        if (imageConsent != null) {
            var consents = new java.util.LinkedHashMap<String, Object>(member.consents() == null ? java.util.Map.of() : member.consents());
            consents.put("imageRights", java.util.Map.of("granted", imageConsent, "at", at, "version", version, "byAccountId", member.accountId()));
            update.set("consents", consents);
        }
        if (!update.getUpdateObject().isEmpty()) {
            update.inc("version", 1);
            mongo.updateFirst(tenantQuery().addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(member.id())
                    .and("accountId").is(member.accountId()).and("status").ne("ERASED")), update, MemberIdentity.class);
        }
    }
}
