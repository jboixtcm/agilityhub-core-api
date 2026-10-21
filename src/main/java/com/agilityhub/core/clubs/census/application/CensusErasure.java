package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusErasureRepository;
import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Dog;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.platform.application.privacy.ErasureDirectory;
import com.agilityhub.core.platform.application.privacy.ErasureEffect;
import com.agilityhub.core.platform.application.privacy.ErasureMember;
import com.agilityhub.core.platform.application.privacy.ErasurePlaceholders;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.application.privacy.MemberDataPackage;
import com.agilityhub.core.platform.application.privacy.MemberDataSource;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.springframework.core.annotation.Order;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Component;

/**
 * S14 WP-14-D, the census side: the facts the erasure reads (R-14-14, R-14-16) and the pseudonymisation of R-14-15's census
 * rows — `Member`, `Dog`, `DogDocument` (their files to delete), and the free text of `InactivityPeriod` and `LeaveRequest`. It runs
 * after the steps that still read the member (payments reads the card customer, S12 `forgetCustomer`).
 */
@Component
@Order(100)
public class CensusErasure implements ErasureDirectory, ErasureStep, MemberDataSource {
    static final String ATTACHMENTS = "attachments";
    private final CensusRepository<Member> members;
    private final CensusRepository<Dog> dogs;
    private final CensusErasureRepository erasure;
    private final CensusAccess access;
    public CensusErasure(CensusRepository<Member> members, CensusRepository<Dog> dogs, CensusErasureRepository erasure, CensusAccess access) {
        this.members = members; this.dogs = dogs; this.erasure = erasure; this.access = access;
    }

    @Override public Optional<ErasureMember> member(String memberId) { return members.findById(memberId).map(this::view); }
    @Override public List<ErasureMember> ofAccount(String accountId) {
        return members.matching(Criteria.where("accountId").is(accountId)).stream().map(this::view).toList();
    }
    @Override public List<ErasureMember> retentionDue(LocalDate leftOnOrBefore, Instant rejectedOnOrBefore) {
        ZoneId zone = ZoneId.of(access.config().club().timeZone());
        return members.matching(Criteria.where("status").is("LEFT").and("erasedAt").is(null)).stream().filter(member -> {
            if ("SIGNUP_REJECTED".equals(member.leftReason)) { return member.leftAt != null && !member.leftAt.isAfter(rejectedOnOrBefore); }
            LocalDate left = member.leaveDate != null ? member.leaveDate : member.leftAt == null ? null : member.leftAt.atZone(zone).toLocalDate();
            return left != null && !left.isAfter(leftOnOrBefore);
        }).map(this::view).toList();
    }
    @Override public List<ErasureMember> erased() {
        var erased = members.matching(Criteria.where("erasedAt").ne(null));
        if (erased.isEmpty()) { return List.of(); }
        var dogsOf = new java.util.HashMap<String, List<String>>();
        dogs.matching(Criteria.where("memberId").in(erased.stream().map(member -> member.id).toList()))
                .forEach(dog -> dogsOf.computeIfAbsent(dog.memberId, ignored -> new java.util.ArrayList<>()).add(dog.id));
        return erased.stream().map(member -> view(member, dogsOf.getOrDefault(member.id, List.of()))).toList();
    }
    private ErasureMember view(Member member) {
        return view(member, dogs.matching(Criteria.where("memberId").is(member.id)).stream().map(dog -> dog.id).toList());
    }
    private static ErasureMember view(Member member, List<String> dogIds) {
        return new ErasureMember(member.id, member.accountId, member.memberNumber, member.status, member.leaveDate, member.leftAt,
                member.leftReason, member.erasedAt, dogIds);
    }

    @Override public ErasureEffect erase(ErasureSubject subject) {
        var pending = subject.members().stream().filter(member -> !member.erased()).toList();
        if (pending.isEmpty()) { return ErasureEffect.NONE; }
        var ids = pending.stream().map(ErasureMember::id).toList();
        var effect = ErasureEffect.builder();
        erasure.dogPhotos(ids).forEach(key -> effect.file(ATTACHMENTS, key));
        var dogIds = pending.stream().flatMap(member -> member.dogIds().stream()).toList();
        erasure.eraseDocuments(dogIds, subject.at()).forEach(key -> effect.file(ATTACHMENTS, key));
        effect.count("dogs", erasure.eraseDogs(ids, subject.placeholders().dog(), subject.at()));
        effect.count("inactivity_periods+leave_requests", erasure.eraseLifecycleText(ids));
        long erased = 0;
        for (var member : pending) {
            var stored = members.findById(member.id()).orElse(null);
            if (stored == null) { continue; }
            if (erasure.eraseMember(stored, subject.placeholders().member(), ErasurePlaceholders.number(member.memberNumber()), subject.requestId(), subject.at())) { erased++; }
        }
        // E11-T16: the other applicants' claims that name an erased member as their group's holder.
        return effect.count("members", erased + erasure.forgetClaimedHolders(ids, subject.at())).build();
    }

    /**
     * S14 R-14-13, the census sections: `member` (every field, the IBAN masked), `consents[]`, `notificationPreferences`, `dogs[]`
     * (chip, level history, licences, the instructor's note and remarks, the documents as a list), `inactivityPeriods[]`,
     * `leaveRequests[]` and `familyGroup` (id and role only); the dogs' photos and documents go to `fitxers/gossos/`.
     */
    @Override @SuppressWarnings("unchecked")
    public void contribute(ErasureMember subject, MemberDataPackage data) {
        var stored = erasure.documents("members", "_id", List.of(subject.id())).stream().findFirst().orElse(null);
        if (stored == null) { return; }
        var member = MemberDataPackage.row(stored);
        if (member.get("consents") instanceof List<?> consents) { data.section("consents", consents); }
        else if (member.get("consents") instanceof java.util.Map<?, ?> legacy) { data.section("consents", List.copyOf(legacy.values())); }
        data.section("notificationPreferences", member.remove("notificationPreferences"));
        member.remove("consents");
        if (member.get("paymentMethod") instanceof java.util.Map<?, ?> method) { member.put("paymentMethod", masked((java.util.Map<String, Object>) method)); }
        data.section("member", member);
        if (stored.get("familyGroupId") != null) {
            var group = erasure.documents("family_groups", "_id", List.of(stored.getString("familyGroupId"))).stream().findFirst().orElse(null);
            data.section("familyGroup", java.util.Map.of("id", stored.getString("familyGroupId"),
                    "role", group != null && subject.id().equals(group.getString("holderMemberId")) ? "HOLDER" : "MEMBER"));
        }
        var documents = erasure.documents("dog_documents", "dogId", subject.dogIds());
        var dogs = new java.util.ArrayList<Object>();
        for (var dog : erasure.documents("dogs", "memberId", List.of(subject.id()))) {
            String dogId = dog.getString("_id");
            var row = MemberDataPackage.row(dog);
            var own = documents.stream().filter(document -> dogId.equals(document.getString("dogId"))).toList();
            row.put("documents", own.stream().map(MemberDataPackage::row).toList());
            dogs.add(row);
            data.file(ATTACHMENTS, dog.getString("photoFileKey"), "gossos/" + dogId + "/foto-" + baseName(dog.getString("photoFileKey")));
            for (var document : own) {
                for (var file : (List<java.util.Map<String, Object>>) document.getOrDefault("files", List.of())) {
                    if (file.get("removedAt") != null || file.get("fileKey") == null) { continue; }
                    String name = file.get("name") == null ? baseName(file.get("fileKey").toString()) : file.get("name").toString();
                    data.file(ATTACHMENTS, file.get("fileKey").toString(), "gossos/" + dogId + "/documents/" + document.getString("type") + "-" + name);
                }
            }
        }
        data.section("dogs", dogs);
        data.section("inactivityPeriods", erasure.documents("inactivity_periods", "memberId", List.of(subject.id())).stream().map(MemberDataPackage::row).toList());
        data.section("leaveRequests", erasure.documents("leave_requests", "memberId", List.of(subject.id())).stream().map(MemberDataPackage::row).toList());
    }
    @SuppressWarnings("unchecked")
    static java.util.Map<String, Object> masked(java.util.Map<String, Object> method) {
        var result = new java.util.LinkedHashMap<String, Object>(method);
        if (result.containsKey("iban")) { result.put("iban", MemberDataPackage.maskIban(result.get("iban"))); }
        if (result.get("sepa") instanceof java.util.Map<?, ?> sepa) { result.put("sepa", masked((java.util.Map<String, Object>) sepa)); }
        return result;
    }
    static String baseName(String key) { return key == null ? null : key.substring(key.lastIndexOf('/') + 1); }
}
