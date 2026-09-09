package com.agilityhub.core.clubs.census.application;

import com.agilityhub.core.clubs.census.domain.CensusRules;
import com.agilityhub.core.clubs.census.persistence.*;
import com.agilityhub.core.clubs.followup.application.AttachmentService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.*;
import java.time.*;
import java.util.*;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.stereotype.Service;
import static com.agilityhub.core.clubs.census.application.CensusValues.*;

/** Explicit response allowlists. Nested persistence maps never become response bodies. */
@Service
public class CensusQuery {
    private final CensusAccess access; private final DocumentService documents; private final AttachmentService attachments;
    private final ClubClock clock;
    public CensusQuery(CensusAccess access, DocumentService documents, AttachmentService attachments, ClubClock clock) {
        this.access = access; this.documents = documents; this.attachments = attachments; this.clock = clock;
    }
    private LocalDate today() { return clock.today(TenantContext.require()); }
    public String fullName(Member member) { return String.join(" ", List.of(member.firstName == null ? "" : member.firstName,
            member.lastName1 == null ? "" : member.lastName1, member.lastName2 == null ? "" : member.lastName2)).strip(); }
    public Map<String,Object> status(Member member) {
        var status = CensusRules.status(member.status, member.leaveDate, access.enabled(Module.INACTIVITY)
                ? access.references.inactivityEnd(member.id, today()) : null, member.erasedAt, today());
        return object("kind", status.kind(), "label", status.kind(), "date", status.date());
    }
    public Map<String,Object> member(String id, boolean admin) {
        var member = access.members.require(id);
        var result = object("id", id, "memberNumber", member.memberNumber, "firstName", member.firstName, "lastName1", member.lastName1,
                "lastName2", member.lastName2, "fullName", fullName(member), "contactEmails", contacts(member), "phones", phones(member),
                "address", address(member), "status", member.status, "displayStatus", status(member), "version", member.version());
        if (!admin) {
            result.put("dogs", ownerDogs(id).stream().map(dog -> object("id", dog.id, "name", dog.name, "level", level(dog.levelId))).toList());
            result.put("bookingBlocked", Boolean.TRUE.equals(map(member.bookingBlock).get("active"))); return result;
        }
        result.putAll(object("accountId", member.accountId, "idDocument", select(map(member.idDocument), "type", "number"), "gender", member.gender,
                "birthDate", member.birthDate, "consents", consents(member), "remarks", member.remarks, "internalNotes", member.internalNotes,
                "joinedAt", member.joinedAt, "leaveDate", member.leaveDate, "bookingBlock", block(id), "roles", roles(id),
                "erasedAt", member.erasedAt, "erasureRequestId", member.erasureRequestId));
        if (access.enabled(Module.FAMILY_GROUP)) { result.putAll(object("familyGroupId", member.familyGroupId, "billedViaMemberId", access.billedViaMemberId(id))); }
        if (access.enabled(Module.BILLING)) {
            result.putAll(object("paymentMethod", payment(member), "maskedAccount", map(payment(member)).get("maskedAccount"),
                    "planId", member.planId, "priceId", member.priceId, "nextInvoiceDate", member.nextInvoiceDate,
                    "accountMissing", "SEPA_DD".equals(map(member.paymentMethod).get("type")) && map(payment(member)).get("maskedAccount") == null));
            var plan = access.references.plan(member.planId);
            if (!plan.isEmpty()) { result.put("plan", object("id", member.planId, "name", localized(plan.get("name")), "type", plan.get("type"), "billingMode", plan.get("billingMode"))); }
        }
        return result;
    }
    private List<Map<String,Object>> contacts(Member member) { return rows(member.contactEmails).stream().map(row -> object("email", row.get("email"), "bounced", Boolean.TRUE.equals(row.get("bounced")))).toList(); }
    private List<Map<String,Object>> phones(Member member) { return rows(member.phones).stream().map(row -> select(row, "prefix", "number", "label")).toList(); }
    private Map<String,Object> address(Member member) { return select(map(member.address), "street", "postalCode", "city", "province", "country"); }
    private Map<String,Object> consents(Member member) {
        if (member.consents == null) { return null; }
        return object("privacyPolicy", select(map(member.consents.get("privacyPolicy")), "acceptedAt", "version"),
                "imageRights", select(map(member.consents.get("imageRights")), "granted", "at", "version", "byAccountId"));
    }
    public Map<String,Object> payment(Member member) {
        if (member.paymentMethod == null) { return null; }
        var pay = member.paymentMethod; var sepa = map(pay.getOrDefault("sepa", pay)); var card = map(pay.getOrDefault("card", pay));
        var manual = map(pay.getOrDefault("manual", pay)); String type = string(pay.get("type"));
        return object("type", type, "maskedAccount", "CARD".equals(type) ? (card.get("last4") == null ? null : "···· " + card.get("last4"))
                : (sepa.get("ibanLast4") == null ? CensusRules.maskedIban(string(sepa.get("iban"))) : "···· " + sepa.get("ibanLast4")), "holderName", sepa.get("holderName"), "channel", manual.get("channel"));
    }
    public Map<String,Object> block(String id) {
        var block = map(access.members.require(id).bookingBlock);
        return object("active", Boolean.TRUE.equals(block.get("active")), "reason", block.get("reason"), "since", instant(block.get("since")), "byAccountId", block.get("byAccountId"));
    }
    private List<String> roles(String id) {
        Object raw = access.references.membership(id).get("roles");
        return raw instanceof List<?> list ? list.stream().map(Object::toString).sorted().toList() : List.of();
    }
    private List<Dog> ownerDogs(String id) { return access.dogs.matching(Criteria.where("memberId").is(id)); }
    public Map<String,Object> level(String id) {
        if (!access.levels() || id == null) { return null; }
        var level = access.references.level(id); if (level.isEmpty()) { return null; }
        return object("id", id, "code", level.get("code"), "name", localized(level.get("name")), "color", level.get("color"));
    }
    private String localized(Object value) {
        var names = map(value); if (names.get("values") instanceof Map<?,?>) { names = map(names.get("values")); }
        return string(names.getOrDefault(LocaleContext.current().getLanguage(), names.getOrDefault(access.config().club().defaultLocale(), "")));
    }
    public Map<String,Object> free(String id) {
        var result = access.free(access.dogs.require(id)); var view = object("allowed", result.allowed(), "source", result.source());
        view.put("override", result.override()); return view;
    }
    private Map<String,Object> pack(String id) {
        var pack = access.enabled(Module.PACKS) ? access.references.pack(id, today()) : Map.<String,Object>of(); return pack.isEmpty() ? null : pack;
    }
    private Map<String,Object> note(Dog dog) {
        if (!access.enabled(Module.TASKS) || dog.instructorNote == null) { return null; }
        return object("text", dog.instructorNote.get("text"), "updatedAt", instant(dog.instructorNote.get("updatedAt")), "attachments", attachments.noteAttachments(dog.id));
    }
    private List<Map<String,Object>> licenses(Dog dog) { return rows(dog.licenses).stream().map(row -> select(row, "organisation", "number", "category", "grade", "division")).toList(); }
    public Map<String,Object> dog(String id) {
        var dog = access.dogs.require(id);
        var result = object("id", id, "memberId", dog.memberId, "name", dog.name, "breed", dog.breed, "sex", dog.sex, "birthDate", dog.birthDate,
                "chip", dog.chip, "handlerName", dog.handlerName, "photoUrl", attachments.url(dog.photoFileKey, dog.name), "licenses", licenses(dog),
                "status", dog.status, "registeredAt", dog.registeredAt, "deactivatedAt", dog.deactivatedAt, "deactivationReason", dog.deactivationReason, "version", dog.version());
        if (access.levels()) { result.putAll(object("levelId", dog.levelId, "levelAssignedAt", dog.levelAssignedAt)); }
        if (access.enabled(Module.FREE_TRAINING)) { result.putAll(object("freeTrainingOverride", dog.freeTrainingOverride)); }
        if (access.enabled(Module.TASKS)) { result.putAll(object("instructorNote", note(dog))); }
        return result;
    }
    public Map<String,Object> detail(String id) {
        var dog = access.dogs.require(id); var owner = access.members.require(dog.memberId);
        var result = object("dog", dog(id), "owner", object("id", owner.id, "fullName", fullName(owner), "memberNumber", owner.memberNumber, "status", owner.status),
                "documents", documents.list(id), "licenses", licenses(dog), "version", dog.version(), "pack", pack(id));
        if (access.levels()) { result.putAll(object("level", level(dog.levelId), "levelHistory", rows(dog.levelHistory).stream().map(row -> select(row, "levelId", "from", "to", "byAccountId")).toList())); }
        if (access.enabled(Module.FREE_TRAINING)) { result.put("freeTraining", free(id)); }
        if (access.enabled(Module.TASKS)) { var tasks = tasks(id); result.put("tasksSummary", select(tasks, "open", "completed")); }
        return result;
    }
    private Map<String,Object> tasks(String dogId) {
        var tasks = access.references.tasks(dogId, clock.now(TenantContext.require()).toInstant().minus(Duration.ofDays(30)));
        return object("open", tasks.stream().filter(task -> task.get("doneAt") == null).count(), "completed", tasks.stream().filter(task -> task.get("doneAt") != null).count(), "items", tasks);
    }
    public Map<String,Object> myDogs() {
        var member = access.me();
        var dogs = ownerDogs(member.id).stream().filter(dog -> "ACTIVE".equals(dog.status)).map(dog -> {
            var result = object("id", dog.id, "name", dog.name, "breed", dog.breed, "sex", dog.sex, "ageYears", CensusRules.age(dog.birthDate, today()),
                    "photoUrl", attachments.url(dog.photoFileKey, dog.name), "level", level(dog.levelId), "documents", documents.list(dog.id), "licenses", licenses(dog), "pack", pack(dog.id));
            if (access.enabled(Module.TASKS)) { result.putAll(object("instructorNote", note(dog), "tasks", tasks(dog.id))); }
            if (access.enabled(Module.FREE_TRAINING)) { result.put("freeTrainingAllowed", access.free(dog).allowed()); }
            return result;
        }).toList();
        return object("dogs", dogs, "canAddDog", "ACTIVE".equals(member.status) && member.erasedAt == null && Boolean.TRUE.equals(access.config().get("signup.enabled", Boolean.class)));
    }
    public Map<String,Object> myProfile() {
        var member = access.me(); var result = object("idDocumentMasked", CensusRules.maskedId(string(map(member.idDocument).get("number"))),
                "firstName", member.firstName, "lastName1", member.lastName1, "lastName2", member.lastName2,
                "contactEmails", contacts(member), "phones", phones(member), "address", address(member), "version", member.version());
        if (access.enabled(Module.BILLING)) { result.putAll(object("paymentMethod", payment(member))); } return result;
    }
    private Map<String,Object> familyMember(String id) {
        var member = access.members.require(id);
        return object("id", id, "fullName", fullName(member), "memberNumber", member.memberNumber, "dogs", ownerDogs(id).stream()
                .filter(dog -> "ACTIVE".equals(dog.status)).map(dog -> object("id", dog.id, "name", dog.name, "levelCode", map(level(dog.levelId)).get("code"))).toList());
    }
    public Map<String,Object> family(String id) {
        access.require(Module.FAMILY_GROUP); var group = access.groups.require(id);
        return object("id", id, "holderMemberId", group.holderMemberId, "memberIds", group.memberIds,
                "members", group.memberIds.stream().map(this::familyMember).toList(), "status", group.status, "version", group.version());
    }
    public Map<String,Object> myFamily() {
        access.require(Module.FAMILY_GROUP); var member = access.me();
        if (member.familyGroupId == null) { return null; }
        var group = access.groups.findById(member.familyGroupId).orElse(null);
        if (group == null || !"ACTIVE".equals(group.status) || !group.memberIds.contains(member.id)) { return null; }
        return object("id", group.id, "holder", familyMember(group.holderMemberId), "members", group.memberIds.stream().map(this::familyMember).toList());
    }
    public Map<String,Object> overview(String id) {
        var member = access.members.require(id);
        var result = object("member", member(id, true), "dogs", ownerDogs(id).stream().map(dog -> {
            var view = object("id", dog.id, "name", dog.name, "breed", dog.breed, "level", level(dog.levelId), "pack", pack(dog.id), "pendingDocuments", documents.pending(dog.id));
            if (access.enabled(Module.FREE_TRAINING)) { view.put("freeTrainingAllowed", access.free(dog).allowed()); } return view;
        }).toList(), "notificationPreferences", preferences(member), "recentAudit", access.references.recentAudit(id));
        if (access.enabled(Module.FAMILY_GROUP) && member.familyGroupId != null) { result.put("familyGroup", family(member.familyGroupId)); }
        if (access.enabled(Module.BILLING)) {
            var invoices = access.references.invoices(id); result.put("recentInvoices", invoices.stream().limit(2).toList()); result.put("invoicesCount", invoices.size());
            // S12 owns the amount calculation; do not fabricate a next-invoice amount from the current price.
        }
        return result;
    }
    private Map<String,Object> preferences(Member member) {
        return select(map(member.notificationPreferences), "categories", "channels", "reminderMinutesBefore", "pushClubNews");
    }
}
