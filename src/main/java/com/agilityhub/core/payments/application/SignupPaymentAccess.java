package com.agilityhub.core.payments.application;

import java.util.Map;

/** Census-owned authorization and confirmed-card updates, injected without a reverse context dependency. */
public interface SignupPaymentAccess {
    Map<String,Object> member(String memberId);
    void authorize(String memberId, String signupToken);
    void card(String memberId, Map<String,Object> card);
    void lock();
    /**
     * The submissions whose rows the member owes (`UpfrontPayment.memberId`), whatever their dogs' status or owner, less
     * those a later public signup superseded (a readmission, R-04-06): the only rows a checkout may charge.
     */
    java.util.List<UpfrontPayments.Submission> submissions(String memberId);
    /** R-04-26 (E3-T13): the language each submission of the scope describes its payment lines in. */
    Map<UpfrontPayments.Submission,String> locales(String memberId, java.util.List<UpfrontPayments.Submission> scope);
}
