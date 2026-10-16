package com.agilityhub.core.platform.application.privacy;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The members of one club an erasure pseudonymises now (S14 R-14-15), with what every context needs to do it the same way:
 * the accounts linked to them and the ones erased with them (`erasedAccountIds`, whose e-mail becomes `pseudonyms`), the
 * placeholders in the club's default locale, and the instant.
 */
public record ErasureSubject(String requestId, String clubId, List<ErasureMember> members, Set<String> erasedAccountIds,
        Map<String, String> pseudonyms, ErasurePlaceholders placeholders, Instant at) {
    public ErasureSubject {
        members = List.copyOf(members); erasedAccountIds = Set.copyOf(erasedAccountIds); pseudonyms = Map.copyOf(pseudonyms);
    }
    public Set<String> memberIds() { return ids(members.stream().map(ErasureMember::id).toList()); }
    public Set<String> dogIds() { return ids(members.stream().flatMap(member -> member.dogIds().stream()).toList()); }
    /** The accounts linked to the members (erased or kept). */
    public Set<String> accountIds() { return ids(members.stream().map(ErasureMember::accountId).filter(java.util.Objects::nonNull).toList()); }
    /** «Abonat suprimit #87» (S14 R-14-15: the invoice snapshot and every label that named the member). */
    public String memberLabel(String memberId) {
        var number = members.stream().filter(member -> member.id().equals(memberId)).map(ErasureMember::memberNumber).findFirst().orElse(null);
        return placeholders.member() + " " + ErasurePlaceholders.number(number);
    }
    private static Set<String> ids(List<String> values) { return java.util.Collections.unmodifiableSet(new LinkedHashSet<>(values)); }
}
