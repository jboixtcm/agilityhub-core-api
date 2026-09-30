package com.agilityhub.core.shared.application;

/** Census-owned, tenant-scoped target validation for identity impersonation. */
@FunctionalInterface
public interface MemberIdentityAccess {
    record Bootstrap(String gender, String lastDogForClass, String lastDogForTraining) { }
    default Bootstrap bootstrap(String memberId) { return new Bootstrap(null, null, null); }
    /** The member's display name as D10 shows it, first name and last names (E5-T29, `/me`'s impersonation); empty when unknown. */
    default java.util.Optional<String> displayName(String memberId) { return java.util.Optional.empty(); }
    String impersonationAccount(String memberId);
}
