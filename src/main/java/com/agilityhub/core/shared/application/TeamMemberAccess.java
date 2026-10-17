package com.agilityhub.core.shared.application;

/** Census projection required by team roles, without a census/catalog dependency cycle. */
public interface TeamMemberAccess {
    record TeamMember(String id, String accountId, String status, String firstName) { }
    TeamMember teamMember(String memberId);
    /** INC-53 (E11-T02): the `memberNumber` of each of these members of the tenant (any status), for a stable demo order. */
    java.util.Map<String, Integer> memberNumbers(java.util.Collection<String> memberIds);
}
