package com.agilityhub.core.shared.application;

import java.util.List;
import org.bson.Document;

/** Mongo read projection equivalent to ConsentGrant; missing evidence is never an affirmative grant. */
public final class ConsentProjection {
    private ConsentProjection() { }

    public static Document granted(String entry) {
        var canonicalPresent = new Document("$ne", List.of(new Document("$type", entry + ".granted"), "missing"));
        var value = new Document("$cond", List.of(canonicalPresent, entry + ".granted", entry + ".accepted"));
        return new Document("$eq", List.of(new Document("$ifNull", List.of(value, false)), true));
    }
}
