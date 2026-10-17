package com.agilityhub.core.clubs.census.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * INC-31 (A37, E11-T02): the members whose IBAN is still stored in clear, for `census:seal-ibans`. An account lives in the
 * payment method (flat, or under `sepa`) and in a pending readmission's submitted payment method; each such place holds either
 * `iban` (written before E11-T02) or `ibanEncrypted` + `ibanLast4`. A platform maintenance read across clubs: no tenant applies.
 */
@Repository
public class ClearIbanRows {
    static final String COLLECTION = "members";
    /** Where a member's account can be stored. */
    public static final List<String> PLACES = List.of("paymentMethod", "paymentMethod.sepa", "readmissionRequest.submitted.paymentMethod",
            "readmissionRequest.submitted.paymentMethod.sepa");
    private final MongoTemplate mongo;
    public ClearIbanRows(MongoTemplate mongo) { this.mongo = mongo; }

    /** Every club's members with an IBAN in clear in any {@link #PLACES}, by id. */
    public List<Document> find() {
        var clear = PLACES.stream().map(place -> Criteria.where(place + ".iban").type(org.springframework.data.mongodb.core.schema.JsonSchemaObject.Type.STRING)).toArray(Criteria[]::new);
        var query = Query.query(new Criteria().orOperator(clear)).with(Sort.by("_id"));
        query.fields().include("_id").include("clubId");
        for (String place : PLACES) { query.fields().include(place + ".iban"); }
        return mongo.find(query, Document.class, COLLECTION);
    }

    /** The places of the row that hold an IBAN in clear, with that IBAN. */
    public static Map<String, String> clear(Document row) {
        var found = new LinkedHashMap<String, String>();
        for (String place : PLACES) {
            Object value = row.getEmbedded(List.of((place + ".iban").split("\\.")), Object.class);
            if (value instanceof String iban && !iban.isBlank()) { found.put(place, iban); }
        }
        return found;
    }

    /**
     * Seals each clear IBAN of the row with one update, conditioned on those IBANs being still the ones read: `iban` goes and
     * `ibanEncrypted` + `ibanLast4` come in its place. True when it wrote; false when a write changed them meanwhile (a rerun
     * seals the new values). {@code sealer} gives the stored fields of an IBAN for this row's member.
     */
    public boolean seal(Document row, Function<String, Map<String, Object>> sealer) {
        var clear = clear(row);
        if (clear.isEmpty()) { return false; }
        var match = new ArrayList<Criteria>(List.of(Criteria.where("_id").is(row.get("_id"))));
        var update = new Update();
        clear.forEach((place, iban) -> {
            match.add(Criteria.where(place + ".iban").is(iban));
            sealer.apply(iban).forEach((field, value) -> update.set(place + "." + field, value));
            update.unset(place + ".iban");
        });
        return mongo.updateFirst(Query.query(new Criteria().andOperator(match)), update, COLLECTION).getModifiedCount() == 1;
    }
}
