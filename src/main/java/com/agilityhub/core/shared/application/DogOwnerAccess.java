package com.agilityhub.core.shared.application;

/** Attachment ownership check without coupling followup to census persistence. */
public interface DogOwnerAccess {
    void requireDog(String dogId, boolean ownerOnly, boolean mutation);
}
