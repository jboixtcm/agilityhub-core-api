package com.agilityhub.core.identity.api;

import com.agilityhub.core.identity.domain.Role;
import java.util.List;
import java.util.Set;

public record MeResponse(AccountDto account, MembershipDto membership, List<String> modules) {
    public record AccountDto(String id, String name, String email, String locale) { }
    public record MembershipDto(Set<Role> roles, String memberId, Role defaultProfile) { }
}
