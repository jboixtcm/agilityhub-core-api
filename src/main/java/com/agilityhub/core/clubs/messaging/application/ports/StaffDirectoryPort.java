package com.agilityhub.core.clubs.messaging.application.ports;

import java.util.Collection;
import java.util.List;

/**
 * The staff audiences of R-11-02 in the current club, each person once, with the contact data of their member record
 * (an administrator without one is addressed at the account's e-mail).
 */
public interface StaffDirectoryPort {
    /** `ADMINS`: accounts with an `ACTIVE` membership whose roles contain `ADMIN`. */
    List<MemberContact> admins();
    /** `INSTRUCTORS` without a class: every active instructor of the club. */
    List<MemberContact> instructors();
    /** `INSTRUCTORS` of a class: its `ClassSession.instructorIds`. */
    List<MemberContact> instructorsOf(String classSessionId);
    /** The instructors with these catalog ids (N-08b: the class's new **and** old instructors). */
    List<MemberContact> instructorsByIds(Collection<String> instructorIds);
}
