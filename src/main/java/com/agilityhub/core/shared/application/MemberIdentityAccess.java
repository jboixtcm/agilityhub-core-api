package com.agilityhub.core.shared.application;

/** Census-owned, tenant-scoped target validation for identity impersonation. */
@FunctionalInterface
public interface MemberIdentityAccess {
    record Bootstrap(String gender, String lastDogForClass, String lastDogForTraining) { }
    default Bootstrap bootstrap(String memberId) { return new Bootstrap(null, null, null); }
    String impersonationAccount(String memberId);
}
