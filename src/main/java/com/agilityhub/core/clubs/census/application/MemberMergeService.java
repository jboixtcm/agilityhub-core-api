package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.time.Clock;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Explicit duplicate consolidation. Source identity, invoices and audit history remain accessible. */
@Service
public class MemberMergeService {
    private final CensusAccess census;
    private final CensusEvents events;
    private final List<MemberMergeParticipant> participants;
    private final Clock clock;
    private final ClubClock local;
    private final AuditWriter audit;
    public MemberMergeService(CensusAccess census, CensusEvents events, List<MemberMergeParticipant> participants, Clock clock, ClubClock local, AuditWriter audit) {
        this.census = census; this.events = events; this.participants = participants; this.clock = clock; this.local = local; this.audit = audit;
    }
    private List<String> blockers(Member source, Member target) {
        var result = new ArrayList<String>();
        if (source.id.equals(target.id)) result.add("SAME_MEMBER");
        if (source.mergedIntoMemberId != null || target.mergedIntoMemberId != null) result.add("ALREADY_MERGED");
        if (!"ACTIVE".equals(target.status)) result.add("TARGET_NOT_ACTIVE");
        if (source.readmissionPending() || target.readmissionPending() || "PENDING".equals(source.status)) result.add("SIGNUP_PENDING");
        if (source.accountId != null && target.accountId != null && !source.accountId.equals(target.accountId)) result.add("DIFFERENT_ACCESS_ACCOUNTS");
        var roles = rows(List.of(census.references.membership(source.id))).stream().flatMap(m -> ((List<?>)m.getOrDefault("roles", List.of())).stream()).map(Object::toString).toList();
        if (roles.contains("ADMIN") || roles.contains("INSTRUCTOR")) result.add("SOURCE_IS_STAFF");
        if (source.familyGroupId != null && target.familyGroupId != null && !source.familyGroupId.equals(target.familyGroupId)) result.add("DIFFERENT_FAMILY_GROUPS");
        if (census.dogs.matching(Criteria.where("memberId").is(source.id)).stream().anyMatch(d -> "PENDING".equals(d.status))) result.add("DOG_SIGNUP_PENDING");
        for (var participant : participants) result.addAll(participant.blockers(source.id, target.id));
        return result;
    }
    public Map<String,Object> preview(String sourceId, String targetId) {
        var source = census.mutableMember(sourceId); var target = census.mutableMember(targetId);
        var counts = new LinkedHashMap<String,Long>();
        counts.put("dogs", (long)census.dogs.matching(Criteria.where("memberId").is(sourceId)).size());
        for (var participant : participants) counts.putAll(participant.preview(sourceId));
        return object("source", summary(source), "target", summary(target), "counts", counts, "blockers", blockers(source, target));
    }
    private String fullName(Member member) { return java.util.stream.Stream.of(member.firstName, member.lastName1, member.lastName2).filter(Objects::nonNull).filter(v -> !v.isBlank()).collect(java.util.stream.Collectors.joining(" ")); }
    private Map<String,Object> summary(Member member) {
        return object("id", member.id, "fullName", fullName(member), "memberNumber", member.memberNumber, "version", member.version());
    }
    @Transactional
    public Map<String,Object> merge(String sourceId, String targetId, long sourceVersion, long targetVersion) {
        census.members.lock(); var source = census.mutableMember(sourceId); var target = census.mutableMember(targetId);
        census.members.lockBookingEligibility(sourceId); census.members.lockBookingEligibility(targetId);
        version(source.version(), sourceVersion); version(target.version(), targetVersion);
        var blocked = blockers(source, target);
        if (!blocked.isEmpty()) throw new ApiException(ErrorCode.INVALID_STATE, object("reason", "MERGE_CONFLICT", "blockers", blocked));
        var before = preview(sourceId, targetId);
        for (var participant : participants) participant.merge(sourceId, targetId);
        for (var dog : census.dogs.matching(Criteria.where("memberId").is(sourceId))) {
            dog.memberId = targetId; census.dogs.save(dog);
            events.emit("DogTransferred", "Dog", dog.id, object("dogId", dog.id, "memberId", targetId, "fromMemberId", sourceId, "toMemberId", targetId));
        }
        if (target.accountId == null) target.accountId = source.accountId;
        if (source.accountId != null && source.accountId.equals(target.accountId)) source.accountId = null;
        if (source.familyGroupId != null) {
            var group = census.groups.require(source.familyGroupId);
            group.memberIds = group.memberIds.stream().map(id -> id.equals(sourceId) ? targetId : id).distinct().toList();
            if (sourceId.equals(group.holderMemberId)) group.holderMemberId = targetId;
            census.groups.save(group); target.familyGroupId = group.id; source.familyGroupId = null;
        }
        source.mergedIntoMemberId = targetId; source.mergedAt = clock.instant();
        source.status = "LEFT"; source.leaveDate = local.today(TenantContext.require()); source.leftAt = clock.instant();
        source.leftReason = "ADMIN"; source.nextInvoiceDate = null;
        census.members.save(source); census.members.save(target);
        events.emit("MemberUpdated", "Member", targetId, object("memberId", targetId, "diff", object("mergedMemberId", object("before", null, "after", sourceId))));
        events.emit("MemberUpdated", "Member", sourceId, object("memberId", sourceId, "diff", object("mergedIntoMemberId", object("before", null, "after", targetId))));
        audit.write(new AuditCommand(AuditAction.MEMBER_UPDATED, "Member", targetId, targetId, object("mergedMemberId", null), object("mergedMemberId", sourceId), "Merge duplicate member"), before);
        audit.write(new AuditCommand(AuditAction.MEMBER_UPDATED, "Member", sourceId, sourceId, object("mergedIntoMemberId", null), object("mergedIntoMemberId", targetId), "Merge duplicate member"));
        return before;
    }
}
