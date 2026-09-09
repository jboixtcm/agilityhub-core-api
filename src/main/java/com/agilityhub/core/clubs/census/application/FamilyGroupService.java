package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.*;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

@Service
public class FamilyGroupService {
    private final CensusAccess access; private final CensusEvents events;
    public FamilyGroupService(CensusAccess access, CensusEvents events) { this.access = access; this.events = events; }
    @Transactional
    @Audited(action = AuditAction.FAMILY_GROUP_CHANGED, entityType = "'FamilyGroup'", entity = "#result")
    public String create(String holder, List<String> ids) {
        access.require(Module.FAMILY_GROUP); access.members.lock();
        var group = new FamilyGroup(); group.id = UUID.randomUUID().toString(); group.clubId = TenantContext.require();
        group.holderMemberId = holder; group.memberIds = validate(group.id, holder, ids); group.status = "ACTIVE";
        access.groups.insert(group); synchronize(group, List.of()); return group.id;
    }
    @Transactional
    @Audited(action = AuditAction.FAMILY_GROUP_CHANGED, entityType = "'FamilyGroup'", entity = "#id")
    public void update(String id, String holder, List<String> ids, Long version) {
        access.require(Module.FAMILY_GROUP); access.members.lock(); var group = access.groups.require(id);
        if (!"ACTIVE".equals(group.status)) { throw new ApiException(ErrorCode.INVALID_STATE); }
        CensusValues.version(group.version(), version); var previous = group.memberIds;
        previous.forEach(access::mutableMember); group.memberIds = validate(id, holder, ids); group.holderMemberId = holder;
        access.groups.save(group); synchronize(group, previous);
    }
    @Transactional
    @Audited(action = AuditAction.FAMILY_GROUP_CHANGED, entityType = "'FamilyGroup'", entity = "#id")
    public void dissolve(String id) {
        access.require(Module.FAMILY_GROUP); access.members.lock(); var group = access.groups.require(id);
        group.memberIds.forEach(access::mutableMember); if ("DISSOLVED".equals(group.status)) { return; }
        group.status = "DISSOLVED"; access.groups.save(group); synchronize(group, group.memberIds);
    }
    private List<String> validate(String id, String holder, List<String> ids) {
        if (ids == null || new HashSet<>(ids).size() < 2) { throw new ApiException(ErrorCode.FAMILY_GROUP_TOO_SMALL); }
        if (ids.stream().anyMatch(Objects::isNull) || ids.size() != new HashSet<>(ids).size() || !ids.contains(holder)) { throw invalid("memberIds", "INVALID_VALUE"); }
        for (String memberId : ids) {
            var member = access.mutableMember(memberId);
            if (!"ACTIVE".equals(member.status)) { throw new ApiException(ErrorCode.MEMBER_NOT_ACTIVE); }
            if (member.familyGroupId != null && !member.familyGroupId.equals(id)) {
                var other = access.groups.findById(member.familyGroupId).orElse(null);
                if (other != null && "ACTIVE".equals(other.status)) { throw new ApiException(ErrorCode.FAMILY_GROUP_MEMBER_ALREADY_IN_GROUP); }
            }
        }
        return List.copyOf(ids);
    }
    private void synchronize(FamilyGroup group, List<String> previous) {
        var ids = new LinkedHashSet<>(previous); ids.addAll(group.memberIds);
        for (String id : ids) {
            var member = access.mutableMember(id);
            String next = "ACTIVE".equals(group.status) && group.memberIds.contains(id) ? group.id : null;
            if (!Objects.equals(member.familyGroupId, next)) { member.familyGroupId = next; access.members.save(member); }
        }
        events.emit("FamilyGroupChanged", "FamilyGroup", group.id, object("groupId", group.id, "holderMemberId", group.holderMemberId,
                "before", previous, "after", "ACTIVE".equals(group.status) ? group.memberIds : List.of(), "status", group.status));
    }
}
