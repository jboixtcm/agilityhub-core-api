package com.agilityhub.core.shared.application;

/** Census-owned, tenant-scoped target validation for identity impersonation. */
@FunctionalInterface
public interface MemberIdentityAccess {
    String impersonationAccount(String memberId);
}
