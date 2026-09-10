package com.agilityhub.core.clubs.census.persistence;

import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.*;
import com.agilityhub.core.shared.application.lists.*;
import com.agilityhub.core.shared.domain.TenantEntity;
import com.agilityhub.core.shared.persistence.TenantRepository;
import java.util.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.stereotype.Repository;

/** Census list projection over the Member/Dog aggregate schema. Every join binds clubId. */
@Repository
public class CensusListProjection extends TenantRepository<CensusListProjection.Projection> {
    public record Projection(String id, String clubId) implements TenantEntity { }
    private final ClubConfigService configs;
    private final ClubClock clock;
    public CensusListProjection(MongoTemplate mongo, ClubConfigService configs, ClubClock clock) {
        super(mongo, Projection.class); this.configs = configs; this.clock = clock;
    }
    public ListDataset dataset(ListDefinition definition, boolean members, boolean admin) {
        var stages = members ? memberStages() : dogStages(true);
        Map<String, Object> fields = members ? memberFields(admin) : dogFields();
        fields.keySet().retainAll(definition.fields());
        return new ListDataset(definition, members ? "members" : "dogs", stages, fields, members ? Set.of() : Set.of("freeTraining.override"), this::label);
    }
    private List<Document> memberStages() {
        var stages = new ArrayList<Document>();
        stages.add(dateFields("birthDate", "leaveDate", "nextInvoiceDate"));
        if (configs.get(TenantContext.require()).modules().contains(com.agilityhub.core.platform.application.Module.INACTIVITY)) {
            String today = clock.today(TenantContext.require()).toString();
            stages.add(join("inactivity_periods", "$_id", "memberId", "inactivity", List.of(dateFields("from", "to"),
                    new Document("$match", new Document("from", new Document("$lte", today)).append("to", new Document("$gte", today))
                            .append("status", new Document("$nin", List.of("CANCELLED", "REJECTED", "DENIED")))))));
        }
        stages.add(join("dogs", "$_id", "memberId", "dogs", dogStages(false)));
        stages.add(join("plans", "$planId", "_id", "plan", List.of()));
        stages.add(join("family_groups", "$familyGroupId", "_id", "familyGroup", List.of()));
        stages.add(join("memberships", "$_id", "memberId", "membership", List.of()));
        stages.add(new Document("$set", new Document("fullName", fullName("$"))
                .append("lastName", expr("$concat", fallback("$lastName1", ""), " ", fallback("$lastName2", "")))
                .append("plan", expr("$arrayElemAt", "$plan", 0)).append("familyGroup", expr("$arrayElemAt", "$familyGroup", 0))
                .append("roles", fallback(expr("$arrayElemAt", "$membership.roles", 0), List.of()))
                .append("hasPendingDocuments", expr("$in", true, "$dogs.hasPendingDocuments"))
                .append("displayStatus", memberStatus())));
        Object ledger=new Document("$isArray","$consents");
        Object latestImage=expr("$arrayElemAt",new Document("$filter",new Document("input","$consents").append("as","consent").append("cond",expr("$eq","$$consent.type","IMAGE_USE"))),-1);
        stages.add(new Document("$set",new Document("consents",new Document("$cond",List.of(ledger,new Document("imageRights",latestImage),fallback("$consents",new Document()))))
                .append("pendingDogs",new Document("$filter",new Document("input","$dogs").append("as","dog").append("cond",expr("$eq","$$dog.status","PENDING"))))));
        stages.add(join("upfront_payments","$_id","memberId","signupPayments",List.of()));
        stages.add(new Document("$set",new Document("signupPending",expr("$gt",expr("$size","$pendingDogs"),0))));
        var warnings=new ArrayList<Object>();
        warnings.add(new Document("$cond",List.of(expr("$ne",fallback("$consents.imageRights.granted",false),true),List.of("NO_IMAGE_CONSENT"),List.of())));
        warnings.add(new Document("$cond",List.of(expr("$in",true,"$pendingDogs.hasPendingDocuments"),List.of("DOCUMENT_PENDING"),List.of())));
        var config=configs.get(TenantContext.require());
        if(config.modules().contains(com.agilityhub.core.platform.application.Module.BILLING)) {
            warnings.add(new Document("$cond",List.of(expr("$and",expr("$eq","$paymentMethod.type","SEPA_DD"),expr("$eq",fallback("$paymentMethod.iban",""),"")),List.of("ACCOUNT_NOT_PROVIDED"),List.of())));
            Object unpaid=new Document("$filter",new Document("input","$signupPayments").append("as","payment").append("cond",expr("$and",expr("$in","$$payment.status",List.of("DUE","PARTIAL","CHECKOUT_PENDING")),expr("$in","$$payment.dogId","$pendingDogs._id"))));
            warnings.add(new Document("$cond",List.of(expr("$gt",expr("$size",unpaid),0),List.of("UPFRONT_UNPAID"),List.of())));
        }
        if(config.modules().contains(com.agilityhub.core.platform.application.Module.FAMILY_GROUP)) warnings.add(new Document("$cond",List.of(expr("$eq","$familyGroupClaim.status","NOT_FOUND_PENDING"),List.of("FAMILY_HOLDER_NOT_FOUND"),List.of())));
        warnings.add(new Document("$cond",List.of(expr("$eq","$signup.readmission",true),List.of("READMISSION"),List.of())));
        stages.add(new Document("$set",new Document("warnings",new Document("$cond",List.of("$signupPending",new Document("$concatArrays",warnings),List.of())))));
        return stages;
    }
    private List<Document> dogStages(boolean owner) {
        var stages = new ArrayList<Document>();
        stages.add(dateFields("birthDate"));
        stages.add(join("levels", "$levelId", "_id", "level", List.of()));
        stages.add(join("dog_documents", "$_id", "dogId", "documents", List.of()));
        stages.add(new Document("$set", new Document("level", expr("$arrayElemAt", "$level", 0))
                .append("licenses", fallback("$licenses", List.of()))
                .append("pendingDocuments", new Document("$setDifference", List.of(requiredDocumentTypes(),
                        map(new Document("$filter", new Document("input", "$documents").append("as", "doc")
                                .append("cond", expr("$eq", "$$doc.state", "RECEIVED"))), "doc", "$$doc.type"))))));
        boolean enabled = Boolean.TRUE.equals(configs.get(TenantContext.require()).get("levels.enabled", Boolean.class));
        stages.add(new Document("$set", new Document("freeTrainingAllowed", fallback("$freeTrainingOverride",
                        enabled ? fallback("$level.grantsFreeTraining", false) : false))
                .append("hasPendingDocuments", expr("$gt", expr("$size", "$pendingDocuments"), 0))
                .append("hasLicense", expr("$gt", expr("$size", "$licenses"), 0))
                .append("displayStatus", status("$status", "$deactivatedAt"))));
        if (owner) {
            stages.add(join("members", "$memberId", "_id", "owner", List.of(new Document("$set", new Document("fullName", fullName("$"))))));
            stages.add(new Document("$set", new Document("owner", expr("$arrayElemAt", "$owner", 0))));
        }
        return stages;
    }
    private Map<String, Object> memberFields(boolean admin) {
        var fields = fields("memberNumber", "fullName", "displayStatus", "joinedAt", "leaveDate", "roles", "birthDate", "gender");
        fields.put("version", fallback("$version", 0));
        fields.put("dogs", map("$dogs", "dog", new Document("id", "$$dog._id").append("name", "$$dog.name").append("level", level("$$dog.level"))));
        fields.put("bookingBlocked", fallback("$bookingBlock.active", false));
        fields.put("contact", new Document("emails", emails()).append("phones", phones()));
        fields.put("plan", reference("$plan")); fields.put("familyGroup", reference("$familyGroup"));
        fields.put("paymentMethod", new Document("type", "$paymentMethod.type")
                .append("maskedAccount", new Document("$cond", List.of(new Document("$ne", java.util.Arrays.asList(fallback("$paymentMethod.ibanLast4", null), null)), new Document("$concat", List.of("···· ", "$paymentMethod.ibanLast4")), masked(fallback("$paymentMethod.sepa.iban", "$paymentMethod.iban"), false))))
                .append("holderName", fallback("$paymentMethod.sepa.holderName", "$paymentMethod.holderName"))
                .append("channel", fallback("$paymentMethod.manual.channel", "$paymentMethod.channel")));
        fields.put("nextInvoiceDate", "$nextInvoiceDate");
        fields.put("imageRights", new Document("granted", "$consents.imageRights.granted").append("at", "$consents.imageRights.at")
                .append("version", "$consents.imageRights.version").append("byAccountId", "$consents.imageRights.byAccountId"));
        fields.put("city", "$address.city"); fields.put("postalCode", "$address.postalCode");
        fields.put("pendingDocuments", new Document("$reduce", new Document("input", "$dogs.pendingDocuments").append("initialValue", List.of())
                .append("in", expr("$setUnion", "$$value", "$$this"))));
        fields.put("freeTraining", expr("$in", true, "$dogs.freeTrainingAllowed"));
        fields.put("idDocument", masked("$idDocument.number", true));
        if(admin) {
            fields.put("signupPending","$signupPending");fields.put("warnings","$warnings");fields.put("signup",new Document("submittedAt","$signup.submittedAt"));
            fields.put("pendingDogs",map("$pendingDogs","dog",new Document("id","$$dog._id").append("name","$$dog.name").append("level",level("$$dog.level"))));
        }
        if (!admin) {
            fields.putAll(fields("firstName", "lastName1", "lastName2", "status"));
            fields.put("contactEmails", emails()); fields.put("phones", phones());
            fields.put("address", new Document("street", "$address.street").append("postalCode", "$address.postalCode")
                    .append("city", "$address.city").append("province", "$address.province").append("country", "$address.country"));
        }
        return fields;
    }
    private Map<String, Object> dogFields() {
        var fields = fields("name", "breed", "sex", "chip", "handlerName", "pendingDocuments", "levelAssignedAt", "registeredAt", "displayStatus");
        fields.put("version", fallback("$version", 0));
        fields.put("level", level("$level"));
        fields.put("owner", new Document("id", "$owner._id").append("fullName", "$owner.fullName")
                .append("memberNumber", "$owner.memberNumber").append("status", "$owner.status"));
        fields.put("handler", new Document("$cond", Arrays.asList(expr("$ne", "$handlerName", "$owner.fullName"), "$handlerName", null)));
        fields.put("freeTraining", new Document("allowed", "$freeTrainingAllowed")
                .append("source", new Document("$cond", List.of(expr("$eq", fallback("$freeTrainingOverride", "LEVEL"), "LEVEL"), "LEVEL", "MANUAL")))
                .append("override", fallback("$freeTrainingOverride", null)));
        fields.put("licenses", map("$licenses", "license", new Document("organisation", "$$license.organisation").append("number", "$$license.number")
                .append("grade", "$$license.grade").append("category", "$$license.category").append("division", "$$license.division")));
        String today = clock.today(TenantContext.require()).toString();
        Object birth = new Document("$convert", new Document("input", "$birthDate").append("to", "date").append("onError", null).append("onNull", null));
        Object yearDifference = expr("$subtract", Integer.parseInt(today.substring(0, 4)), new Document("$year", birth));
        Object birthdayLater = expr("$gt", expr("$substrCP", fallback("$birthDate", "0000-00-00"), 5, 5), today.substring(5));
        fields.put("age", expr("$subtract", yearDifference, new Document("$cond", List.of(birthdayLater, 1, 0))));
        fields.put("pack", new Document("id", "$pack.id").append("remaining", "$pack.remaining").append("total", "$pack.total")
                .append("expiresAt", "$pack.expiresAt").append("planName", "$pack.planName"));
        return fields;
    }
    private Document join(String collection, Object local, String foreign, String as, List<Document> rest) {
        var pipeline = new ArrayList<Document>();
        pipeline.add(new Document("$match", new Document("clubId", TenantContext.require())
                .append("$expr", expr("$eq", "$" + foreign, "$$reference"))));
        pipeline.addAll(rest);
        return new Document("$lookup", new Document("from", collection).append("let", new Document("reference", fallback(local, "")))
                .append("pipeline", pipeline).append("as", as));
    }
    private Object level(String path) {
        if (!Boolean.TRUE.equals(configs.get(TenantContext.require()).get("levels.enabled", Boolean.class))) { return "$$REMOVE"; }
        return optional(path, new Document("id", path + "._id").append("code", path + ".code").append("name", localized(path + ".name"))
                .append("color", path + ".color"));
    }
    private Object reference(String path) { return optional(path, new Document("id", path + "._id").append("name", localized(path + ".name"))); }
    private static Object optional(String path, Object value) { return new Document("$cond", List.of(expr("$ne", fallback(path + "._id", ""), ""), value, "$$REMOVE")); }
    private Object localized(String path) {
        String fallbackLocale = configs.get(TenantContext.require()).club().defaultLocale();
        return fallback(path + ".values." + LocaleContext.current().getLanguage(), fallback(path + "." + LocaleContext.current().getLanguage(), fallback(path + ".values." + fallbackLocale, fallback(path + "." + fallbackLocale, ""))));
    }
    private String label(String field, Object value) {
        String collection = switch (field) { case "planId" -> "plans"; case "priceId" -> "prices"; case "levelId", "dogLevelId" -> "levels"; default -> null; };
        if (collection == null) { return value.toString(); }
        var row = mongo.findOne(tenantQuery().addCriteria(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(value)), Document.class, collection);
        if (row == null || !(row.get("name") instanceof Map<?, ?> names)) { return value.toString(); }
        if (names.get("values") instanceof Map<?, ?> values) { names = values; }
        Object translated = names.get(LocaleContext.current().getLanguage());
        if (translated == null) { translated = names.get(configs.get(TenantContext.require()).club().defaultLocale()); }
        return translated == null ? value.toString() : translated.toString();
    }
    private List<String> requiredDocumentTypes() {
        return com.agilityhub.core.clubs.census.application.CensusValues.rows(configs.get(TenantContext.require()).get("census.dogDocumentTypes", List.class)).stream()
                .filter(row -> Boolean.TRUE.equals(row.get("required"))).map(row -> row.get("key").toString()).toList();
    }
    private Object memberStatus() {
        Object inactive = expr("$arrayElemAt", fallback("$inactivity.to", List.of()), 0);
        Object scheduled = status("$status", "$leaveDate");
        Object activeInactivity = new Document("$cond", List.of(expr("$and", expr("$eq", "$status", "ACTIVE"), expr("$ne", fallback(inactive, ""), "")),
                new Document("kind", "INACTIVE_PERIOD").append("label", "INACTIVE_PERIOD").append("date", inactive), scheduled));
        return new Document("$cond", List.of(expr("$ne", fallback("$erasedAt", ""), ""), new Document("kind", "ERASED").append("label", "ERASED"), activeInactivity));
    }
    private Object status(String status, String date) {
        Object kind = new Document("$cond", List.of(expr("$and", expr("$eq", status, "ACTIVE"),
                expr("$gte", date, clock.today(TenantContext.require()).toString())), "LEAVE_SCHEDULED", status));
        return new Document("kind", kind).append("label", kind).append("date", date);
    }
    private static Document dateFields(String... names) {
        var fields = new Document();
        for (String name : names) {
            fields.put(name, new Document("$cond", List.of(expr("$eq", new Document("$type", "$" + name), "date"),
                    new Document("$dateToString", new Document("date", "$" + name).append("format", "%Y-%m-%d").append("timezone", "UTC")), "$" + name)));
        }
        return new Document("$set", fields);
    }
    private static Object fullName(String path) { return new Document("$trim", new Document("input", expr("$concat", fallback(path + "firstName", ""), " ", fallback(path + "lastName1", ""), " ", fallback(path + "lastName2", "")))); }
    private static Object emails() { return map(fallback("$contactEmails", List.of()), "email", new Document("email", "$$email.email").append("bounced", fallback("$$email.bounced", false))); }
    private static Object phones() { return map(fallback("$phones", List.of()), "phone", new Document("prefix", "$$phone.prefix").append("number", "$$phone.number").append("label", "$$phone.label")); }
    private static Object masked(Object path, boolean document) {
        Object input = fallback(path, "");
        Object length = new Document("$strLenCP", input);
        Object tail = expr("$substrCP", input, expr("$max", 0, expr("$subtract", length, document ? 2 : 4)), document ? 2 : 4);
        return new Document("$cond", Arrays.asList(expr("$gt", length, 0), document
                ? expr("$concat", expr("$substrCP", input, 0, 2), "······", tail)
                : expr("$concat", "···· ···· ···· ···· ", tail), null));
    }
    private static Map<String, Object> fields(String... names) { var result = new LinkedHashMap<String, Object>(); for (String name : names) { result.put(name, "$" + name); } return result; }
    private static Document map(Object input, String as, Object in) { return new Document("$map", new Document("input", input).append("as", as).append("in", in)); }
    private static Document expr(String op, Object... values) { return new Document(op, Arrays.asList(values)); }
    private static Document fallback(Object value, Object fallback) { return expr("$ifNull", value, fallback); }
}
