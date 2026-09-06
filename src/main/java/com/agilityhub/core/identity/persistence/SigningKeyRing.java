package com.agilityhub.core.identity.persistence;

import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("signing_keys")
public record SigningKeyRing(@Id String id, String encryptedKeys, long generation, Instant rotatedAt) {
    @Override public String toString() { return "SigningKeyRing[redacted]"; }
}
