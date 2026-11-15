package com.agilityhub.core.clubs.census.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * PRIV-11 (owner's decision of 13-11-2026): the members' sealed IBANs (`ibanEncrypted`), for `census:rekey-ibans`. They live in the
 * same {@link ClearIbanRows#PLACES} as a clear one. A platform maintenance read across clubs: no tenant applies.
 */
@Repository
public class SealedIbanRows {
    private final MongoTemplate mongo;
    public SealedIbanRows(MongoTemplate mongo) { this.mongo = mongo; }

    /** Every club's members with a sealed IBAN in any place, by id: only `_id`, `clubId` and those `ibanEncrypted`. */
    public List<Document> find() {
        var sealed = ClearIbanRows.PLACES.stream().map(place -> Criteria.where(place + ".ibanEncrypted")
                .type(org.springframework.data.mongodb.core.schema.JsonSchemaObject.Type.STRING)).toArray(Criteria[]::new);
        var query = Query.query(new Criteria().orOperator(sealed)).with(Sort.by("_id"));
        query.fields().include("_id").include("clubId");
        for (String place : ClearIbanRows.PLACES) { query.fields().include(place + ".ibanEncrypted"); }
        return mongo.find(query, Document.class, ClearIbanRows.COLLECTION);
    }

    /** The places of the row that hold a sealed IBAN, with that `ibanEncrypted`. */
    public static Map<String, String> sealed(Document row) {
        var found = new LinkedHashMap<String, String>();
        for (String place : ClearIbanRows.PLACES) {
            Object value = row.getEmbedded(List.of((place + ".ibanEncrypted").split("\\.")), Object.class);
            if (value instanceof String encrypted && !encrypted.isBlank()) { found.put(place, encrypted); }
        }
        return found;
    }

    /**
     * Writes the new `ibanEncrypted` of each given place with one update, conditioned on every one of them still holding the value
     * read in {@code row} (`ibanLast4` and the rest of the method stay as they are). True when it wrote; false when a write changed
     * them meanwhile (a rerun re-encrypts the new values).
     */
    public boolean replace(Document row, Map<String, String> replacements) {
        if (replacements.isEmpty()) { return false; }
        var read = sealed(row);
        var match = new ArrayList<Criteria>(List.of(Criteria.where("_id").is(row.get("_id"))));
        var update = new Update();
        replacements.forEach((place, encrypted) -> {
            match.add(Criteria.where(place + ".ibanEncrypted").is(read.get(place)));
            update.set(place + ".ibanEncrypted", encrypted);
        });
        return mongo.updateFirst(Query.query(new Criteria().andOperator(match)), update, ClearIbanRows.COLLECTION).getModifiedCount() == 1;
    }
}
