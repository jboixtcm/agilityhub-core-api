package com.agilityhub.core.shared.application;

import java.time.Instant;

/** Census-owned access to the current account's member profile, always tenant scoped. */
public interface MemberOnboardingAccess {
    default Boolean imageConsent(String memberId, String accountId) { return null; }
    String phone(String memberId, String accountId);
    void update(String memberId, String accountId, String phone, Boolean imageConsent, String version, Instant at);
}
