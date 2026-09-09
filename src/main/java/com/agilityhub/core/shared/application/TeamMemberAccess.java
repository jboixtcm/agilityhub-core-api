package com.agilityhub.core.shared.application;

/** Census projection required by team roles, without a census/catalog dependency cycle. */
public interface TeamMemberAccess {
    record TeamMember(String id, String accountId, String status, String firstName) { }
    TeamMember teamMember(String memberId);
}
