package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.TenantWriteFence;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;

/**
 * S14 R-14-15, the census part: field-by-field pseudonymisation of members, dogs, dog documents and the free text of inactivity
 * and leave requests in the current club. Each write only touches documents not erased yet (`erasedAt` absent), so a second run
 * changes nothing; the census version moves like any other write.
 */
@Repository
public class CensusErasureRepository {
    private final MongoTemplate mongo;
    private final TenantWriteFence writes;
    public CensusErasureRepository(MongoTemplate mongo, TenantWriteFence writes) { this.mongo = mongo; this.writes = writes; }

    private Criteria club() { return Criteria.where("clubId").is(TenantContext.require()); }

    /** One member: what R-14-15 keeps (number, status, gender, dates, plan, consents, family group) and the placeholders. */
    public boolean eraseMember(Member member, String firstName, String lastName1, String requestId, Instant at) {
        writes.written(TenantContext.require());
        var update = new Update().set("firstName", firstName).set("lastName1", lastName1)
                .unset("lastName2").unset("birthDate").unset("address").unset("remarks").unset("internalNotes")
                .set("contactEmails", List.of()).set("phones", List.of())
                .set("idDocument", Map.of("type", "ERASED", "number", "ERASED-" + member.id))
                .unset("signup.userAgent").unset("familyGroupClaim.holderName").unset("familyGroupClaim.dogName").unset("bookingBlock.reason")
                .unset("lastDogForClass").unset("lastDogForTraining").unset("notificationPreferences").unset("readmissionRequest")
                .set("erasedAt", at).set("erasureRequestId", requestId).set("updatedAt", at).inc("version", 1);
        var method = erasedPaymentMethod(member.paymentMethod);
        if (method == null) { update.unset("paymentMethod"); } else { update.set("paymentMethod", method); }
        // By collection name: the entity's date converter would try to convert `$unset`'s flag.
        return mongo.updateFirst(Query.query(club()).addCriteria(Criteria.where("_id").is(member.id).and("erasedAt").is(null)), update, "members")
                .getModifiedCount() == 1;
    }

    /**
     * R-14-15 with S04 R-04-12 (E11-T16): another applicant's `FOUND` claim names the erased holder and the holder's dog
     * (`holderName`/`dogName`, as the applicant typed them): the names go, `status` and `holderMemberId` stay, and the census
     * version moves. A `NOT_FOUND_PENDING` claim carries no holder id: nothing ties its names to the erased member.
     */
    public long forgetClaimedHolders(Collection<String> holderIds, Instant at) {
        if (holderIds.isEmpty()) { return 0; }
        writes.written(TenantContext.require());
        var named = new Criteria().orOperator(Criteria.where("familyGroupClaim.holderName").exists(true), Criteria.where("familyGroupClaim.dogName").exists(true));
        var claims = Query.query(club()).addCriteria(Criteria.where("familyGroupClaim.holderMemberId").in(holderIds).and("_id").nin(holderIds)).addCriteria(named);
        return mongo.updateMulti(claims, new Update().unset("familyGroupClaim.holderName").unset("familyGroupClaim.dogName").set("updatedAt", at).inc("version", 1),
                "members").getModifiedCount();
    }

    /**
     * R-14-15 `paymentMethod → només type + mandateRef + mandateSignedAt`: IBAN (clear or encrypted), holder, `holderTaxId` and the
     * card data go; the mandate stays where it was (the top level or the `sepa` block).
     */
    @SuppressWarnings("unchecked")
    static Map<String, Object> erasedPaymentMethod(Map<String, Object> method) {
        if (method == null) { return null; }
        var result = new LinkedHashMap<String, Object>();
        if (method.get("type") != null) { result.put("type", method.get("type")); }
        copyMandate(method, result);
        if (method.get("sepa") instanceof Map<?, ?> sepa) {
            var kept = new LinkedHashMap<String, Object>(); copyMandate((Map<String, Object>) sepa, kept);
            if (!kept.isEmpty()) { result.put("sepa", kept); }
        }
        return result;
    }
    private static void copyMandate(Map<String, Object> from, Map<String, Object> to) {
        for (String key : List.of("mandateRef", "mandateSignedAt")) { if (from.get(key) != null) { to.put(key, from.get(key)); } }
    }

    /** The members' dogs: the name placeholder, no chip, licences, notes, handler or photo; breed, sex, birth date, level and history stay. */
    public long eraseDogs(Collection<String> memberIds, String name, Instant at) {
        writes.written(TenantContext.require());
        var update = new Update().set("name", name).set("nameKey", Dog.nameKey(name)).set("licenses", List.of())
                .unset("chip").unset("instructorNote").unset("remarks").unset("remarksMeta.updatedByName").unset("photoFileKey")
                .unset("handlerName").unset("signup.userAgent").unset("readmissionRequest")
                .set("erasedAt", at).set("updatedAt", at).inc("version", 1);
        return mongo.updateMulti(Query.query(club()).addCriteria(Criteria.where("memberId").in(memberIds).and("erasedAt").is(null)), update, "dogs")
                .getModifiedCount();
    }
    /**
     * R-14-15 (E11-T16 round 2, the S1 shape): the erased accounts' name as the author of any dog's observations of the club
     * (`remarksMeta.updatedByName`, S10 R-10-12), another member's dog included; the account id, the remarks and the
     * observations' version stay (the name is not part of what a form sends back).
     */
    public long forgetRemarksAuthors(Collection<String> accountIds) {
        if (accountIds.isEmpty()) { return 0; }
        writes.written(TenantContext.require());
        return mongo.updateMulti(Query.query(club()).addCriteria(Criteria.where("remarksMeta.updatedByAccountId").in(accountIds).and("remarksMeta.updatedByName").exists(true)),
                new Update().unset("remarksMeta.updatedByName"), "dogs").getModifiedCount();
    }
    /** The photo keys of the members' dogs not erased yet (read before {@link #eraseDogs}). */
    public List<String> dogPhotos(Collection<String> memberIds) {
        var query = Query.query(club()).addCriteria(Criteria.where("memberId").in(memberIds).and("erasedAt").is(null).and("photoFileKey").ne(null));
        query.fields().include("photoFileKey");
        return mongo.find(query, Dog.class).stream().map(dog -> dog.photoFileKey).toList();
    }

    /**
     * R-14-15 `DogDocument`: each file's name goes and the file is marked removed (its object is deleted from the store after the
     * commit); returns the file keys. A document already erased (every file without a name) is left as it is.
     */
    public List<String> eraseDocuments(Collection<String> dogIds, Instant at) {
        if (dogIds.isEmpty()) { return List.of(); }
        writes.written(TenantContext.require());
        var keys = new ArrayList<String>();
        for (var document : mongo.find(Query.query(club()).addCriteria(Criteria.where("dogId").in(dogIds)), DogDocument.class)) {
            if (document.files == null || document.files.isEmpty()) { continue; }
            boolean changed = false; var files = new ArrayList<Map<String, Object>>();
            for (var file : document.files) {
                var copy = new LinkedHashMap<String, Object>(file);
                if (copy.get("fileKey") != null) { keys.add(copy.get("fileKey").toString()); }
                if (copy.get("name") != null) { copy.put("name", null); changed = true; }
                if (copy.get("removedAt") == null) { copy.put("removedAt", at); changed = true; }
                files.add(copy);
            }
            if (changed) {
                mongo.updateFirst(Query.query(club()).addCriteria(Criteria.where("_id").is(document.id)),
                        new Update().set("files", files).set("updatedAt", at).inc("version", 1), DogDocument.class);
            }
        }
        return keys;
    }

    /** R-14-15: inactivity and leave requests keep ids, states, dates and NPS; their free text goes. */
    public long eraseLifecycleText(Collection<String> memberIds) {
        writes.written(TenantContext.require());
        var query = Query.query(club()).addCriteria(Criteria.where("memberId").in(memberIds));
        long changed = mongo.updateMulti(query, new Update().unset("comments").unset("decision.note"), "inactivity_periods").getModifiedCount();
        return changed + mongo.updateMulti(Query.query(club()).addCriteria(Criteria.where("memberId").in(memberIds)),
                new Update().unset("comment").unset("decision.note"), "leave_requests").getModifiedCount();
    }

    /** S14 R-14-13: the stored documents of a collection of the current club whose `field` is one of `values` (the package reads them raw). */
    public List<org.bson.Document> documents(String collection, String field, Collection<String> values) {
        if (values.isEmpty()) { return List.of(); }
        return mongo.find(Query.query(club()).addCriteria(Criteria.where(field).in(values)).with(org.springframework.data.domain.Sort.by("_id")),
                org.bson.Document.class, collection);
    }
}
