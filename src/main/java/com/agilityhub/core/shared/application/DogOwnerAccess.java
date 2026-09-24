package com.agilityhub.core.shared.application;

import java.util.Optional;

/** Attachment ownership check without coupling followup to census persistence. */
public interface DogOwnerAccess {
    void requireDog(String dogId, boolean ownerOnly, boolean mutation);
    /** The owner (`memberId`) of a dog of the open tenant; empty when the club has no such dog (E6-T01, S10 follow-up guards). */
    Optional<String> ownerOf(String dogId);
}
