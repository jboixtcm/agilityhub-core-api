package com.agilityhub.core.shared.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * What S10 follow-up (tasks, observations, D14, N-20/21/22; E6-T03) reads from and writes to the census, which implements
 * it: `clubs.census` depends on `clubs.followup` for the attachments, so `clubs.followup` never imports it and never
 * writes the `dogs` or `members` collections. Tenant-scoped; unknown ids are absent.
 */
public interface FollowupCensusAccess {
    /** A dog as follow-up shows it; `levelCode` is null with `levels.enabled = false` (R-10-16). */
    record Dog(String id, String name, String status, String memberId, String levelCode) { }
    /**
     * The owner of a dog: D14 `memberName` (full name), `doneBy` of a member (first name, gender) and N-20's recipient (the
     * account, the contact email and the language of the signup when there is no account). Erased members are absent.
     */
    record Member(String id, String firstName, String fullName, String gender, String accountId, String email, String locale) { }
    /** `Dog.instructorNote` (S03 R-03-17): the member's note, read-only here. */
    record Note(String text, Instant updatedAt, String updatedByAccountId) { }
    /**
     * `Dog.remarks` + `Dog.remarksMeta` (R-10-12); `version` (`remarksMeta.version`, 0 before the first change) counts the
     * observation changes and is the optimistic lock of `PUT /dogs/{id}/observations`: other writes of the dog leave it.
     */
    record Remarks(String text, Instant updatedAt, String updatedByAccountId, String updatedByName, long version) { }

    /**
     * D14's search (S10 §6, CONVENCIONS_API §4, E6-T06): the dogs whose name contains the text, the members whose full name
     * («first last1 last2», as {@link Member#fullName}) does, and the dogs whose member note does; any case, taken literally.
     * Erased members are absent, as in {@link #members}.
     */
    record Matches(Set<String> dogIds, Set<String> memberIds, Set<String> noteDogIds) { }

    Map<String, Dog> dogs(Collection<String> dogIds);
    /** {@link Matches} of a non-blank {@code text}. */
    Matches search(String text);
    default Optional<Dog> dog(String dogId) { return Optional.ofNullable(dogs(List.of(dogId)).get(dogId)); }
    /** The dogs a member owns, any status (the E6-T04 demo seed finds the holder's dog by its name). */
    Map<String, Dog> dogsOf(String memberId);
    Map<String, Member> members(Collection<String> memberIds);
    Optional<Note> instructorNote(String dogId);
    Optional<Remarks> remarks(String dogId);
    /**
     * Writes the observations through the census (`DogService`): `DogUpdated{diff: remarks}` and an `AuditEntry{DOG_UPDATED}`;
     * a stale observations `version` is 409 STALE_VERSION and the reused dog of a pending readmission 409 INVALID_STATE
     * (READMISSION_PENDING).
     */
    Remarks saveRemarks(String dogId, String text, long version, String updatedByName);
}
