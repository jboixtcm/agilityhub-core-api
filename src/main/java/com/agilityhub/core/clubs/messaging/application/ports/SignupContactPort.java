package com.agilityhub.core.clubs.messaging.application.ports;

import java.util.Optional;

/** S11 R-11-02 `APPLICANT`: the address, language and name of the person who applied (a member still without an account). */
public interface SignupContactPort {
    /** The applicant of the member's signup (`signup.locale`, the primary contact address). */
    Optional<ApplicantContact> applicant(String memberId);

    /**
     * @param accountId the account of an existing member who applied (S04 §8 add-dog: «abonat existent», who also gets the
     *                  APP copy of N-01/N-03); null for a new applicant
     */
    record ApplicantContact(String email, String locale, String displayName, String accountId) {
        public ApplicantContact(String email, String locale, String displayName) { this(email, locale, displayName, null); }
        @Override public String toString() { return "ApplicantContact[redacted]"; }
    }
}
