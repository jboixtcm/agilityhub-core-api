package com.agilityhub.core.clubs.messaging.application.ports;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * The census as the notification engine reads it (tenant-scoped, the current club): members with their contact data, dogs
 * and preferences. An erased member is absent. Implemented by the census; the null object (no census) knows nobody.
 */
public interface MemberDirectoryPort {
    Optional<MemberContact> find(String memberId);
    /** Many members in one read; unknown and erased ids are absent. */
    List<MemberContact> findAll(Collection<String> memberIds);
    /** The member of the account in the current club, if any (the booker of a family dog, a staff member's contact data). */
    Optional<MemberContact> byAccount(String accountId);
    /** E7-T04 (R-11-13 «Enviar comunicat»): the members of an explicit selection. */
    default List<MemberContact> byIds(Collection<String> memberIds) { return findAll(memberIds); }
    /**
     * E7-T04 (R-11-13): the members `GET /members` would list with these universal-list filters and `q`. Until E7-T04
     * serves announcements no caller needs it, and the default knows nobody.
     */
    default List<MemberContact> byFilters(List<String> filters, String q) { return List.of(); }
    /** The members of the club with at least one contact e-mail equal to the address (case-insensitive), for a bounce. */
    List<String> membersWithEmail(String address);
    /**
     * A dog of the club by id, whoever owns it (R-11-05: the subject dog's `dog_name` and `dog_name_article` for a recipient
     * who is not its owner, e.g. the family-group member who booked it, A20b). The null object knows none.
     */
    default Optional<MemberContact.DogContact> dog(String dogId) { return Optional.empty(); }
}
