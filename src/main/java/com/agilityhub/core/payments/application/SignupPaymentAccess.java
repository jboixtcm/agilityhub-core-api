package com.agilityhub.core.payments.application;

import java.util.Map;

/** Census-owned authorization and confirmed-card updates, injected without a reverse context dependency. */
public interface SignupPaymentAccess {
    Map<String,Object> member(String memberId);
    void authorize(String memberId, String signupToken);
    void card(String memberId, Map<String,Object> card);
    void lock();
    /**
     * The dogs of the member's current submission (whatever their status) and every pending dog, each with its own
     * submission: the only rows a checkout may charge (E3-T08).
     */
    java.util.List<UpfrontPayments.Submission> submissions(String memberId);
}
