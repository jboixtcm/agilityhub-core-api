package com.agilityhub.core.clubs.census.application;

import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.query.Criteria;
import com.agilityhub.core.shared.application.CurrentUser;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** S07 reads census through this tenant-scoped application boundary. */
@Service
public class ActivityMemberAccess {
    public record Dog(String id, String status, String levelId) { }
    public record Member(String id, String accountId, String name, String lastName, String number, String status, boolean membershipActive,
                         LocalDate leaveDate, Map<String,Object> bookingBlock, List<Dog> dogs, List<Map<String,Object>> inactivity,
                         List<Map<String,Object>> phones, List<String> emails) { }
    private final CensusAccess access;
    public ActivityMemberAccess(CensusAccess access) { this.access = access; }
    public Map<String,Object> notificationPreferences(String memberId) { return map(access.members.require(memberId).notificationPreferences); }
    public String me() { return access.me().id; }
    public List<String> activeIds() { return access.members.matching(Criteria.where("status").is("ACTIVE").and("erasedAt").is(null)).stream().map(m -> m.id).toList(); }
    public Map<String,Object> actorBlock() {
        var user = CurrentUser.current();
        if (user == null) return Map.of();
        String account = user.impersonation() == null ? user.accountId() : user.impersonation().actorAccountId();
        return access.members.matching(Criteria.where("accountId").is(account)).stream().findFirst().map(m -> map(m.bookingBlock)).orElse(Map.of());
    }
    public Member member(String id) {
        var m = access.members.require(id);
        return new Member(m.id,m.accountId,java.util.stream.Stream.of(m.firstName,m.lastName1,m.lastName2).filter(Objects::nonNull).collect(java.util.stream.Collectors.joining(" ")),
                Objects.toString(m.lastName1, ""),Objects.toString(m.memberNumber, ""),m.status,
                "ACTIVE".equals(access.references.membership(id).get("status")),m.leaveDate,map(m.bookingBlock),
                access.dogs.matching(Criteria.where("memberId").is(id)).stream().map(d -> new Dog(d.id,d.status,d.levelId)).toList(),
                access.references.approvedInactivity(id),rows(m.phones),rows(m.contactEmails).stream().map(e -> string(e.get("email"))).filter(Objects::nonNull).toList());
    }
}
