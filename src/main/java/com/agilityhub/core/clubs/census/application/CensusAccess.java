package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.domain.*;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class CensusAccess implements DogOwnerAccess {
    final CensusRepository<Member> members; final CensusRepository<Dog> dogs;
    final CensusRepository<FamilyGroup> groups; final CensusReferences references;
    private final ClubConfigService configs;
    public CensusAccess(CensusRepository<Member> members, CensusRepository<Dog> dogs, CensusRepository<FamilyGroup> groups,
            CensusReferences references, ClubConfigService configs) {
        this.members = members; this.dogs = dogs; this.groups = groups; this.references = references; this.configs = configs;
    }
    public ClubConfig config() { return configs.get(TenantContext.require()); }
    public boolean enabled(Module module) { return config().modules().contains(module); }
    public void require(Module module) { if (!enabled(module)) { throw new ApiException(ErrorCode.MODULE_DISABLED); } }
    public boolean levels() { return Boolean.TRUE.equals(config().get("levels.enabled", Boolean.class)); }
    public boolean role(String role) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.getAuthorities().stream().anyMatch(granted -> granted.getAuthority().equals("ROLE_" + role));
    }
    public Member me() {
        var user = CurrentUser.current();
        if (user == null) { throw new ApiException(ErrorCode.UNAUTHENTICATED); }
        if (user.impersonation() != null) { return members.require(user.impersonation().memberId()); }
        return members.matching(Criteria.where("accountId").is(user.accountId())).stream().findFirst().orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
    public Member mutableMember(String id) { var member = members.require(id); CensusRules.mutable(member.erasedAt); return member; }
    public Dog mutableDog(String id) { var dog = dogs.require(id); mutableMember(dog.memberId); return dog; }
    public Dog ownDog(String id, boolean mutation) {
        var dog = dogs.require(id); var member = me();
        if (!member.id.equals(dog.memberId)) { throw new ApiException(ErrorCode.FORBIDDEN); }
        if (mutation) { CensusRules.mutable(member.erasedAt); }
        return dog;
    }
    @Override public void requireDog(String dogId, boolean ownerOnly, boolean mutation) {
        if (ownerOnly) { ownDog(dogId, mutation); }
        else if (mutation) { mutableDog(dogId); } else { dogs.require(dogId); }
    }
    public String billedViaMemberId(String memberId) {
        var member = members.require(memberId);
        if (enabled(Module.FAMILY_GROUP) && member.familyGroupId != null) {
            var group = groups.findById(member.familyGroupId).orElse(null);
            if (group != null && "ACTIVE".equals(group.status) && group.memberIds.contains(member.id)) { return group.holderMemberId; }
        }
        return member.id;
    }
    public FreeTrainingPolicy.Result free(Dog dog) {
        return FreeTrainingPolicy.evaluate(dog.freeTrainingOverride, levels() && dog.levelId != null,
                Boolean.TRUE.equals(references.level(dog.levelId).get("grantsFreeTraining")));
    }
}
