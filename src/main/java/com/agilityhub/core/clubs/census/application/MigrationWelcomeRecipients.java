package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.Member;
import java.util.List;
import java.util.Set;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;

/** S18: only active imported members with an access account, projected inside the current tenant. */
@Service
public class MigrationWelcomeRecipients {
    private final CensusRepository<Member> members;
    public MigrationWelcomeRecipients(CensusRepository<Member> members) { this.members = members; }
    public record Recipient(String memberId, String accountId, Integer number, String firstName, String gender) { }
    public List<Recipient> list(Set<Integer> numbers) {
        var query = Criteria.where("status").is("ACTIVE").and("externalIds.playoff").exists(true);
        if (!numbers.isEmpty()) { query.and("memberNumber").in(numbers); }
        return members.matching(query, "accountId", "memberNumber", "firstName", "gender").stream()
                .filter(member -> member.accountId != null)
                .map(member -> new Recipient(member.id, member.accountId, member.memberNumber, member.firstName, member.gender)).toList();
    }
}
