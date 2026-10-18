package com.agilityhub.core.platform.privacy;

import com.agilityhub.core.clubs.catalogs.application.InstructorWriter;
import com.agilityhub.core.clubs.catalogs.persistence.Instructor;
import com.agilityhub.core.clubs.catalogs.persistence.Plan;
import com.agilityhub.core.clubs.catalogs.persistence.Price;
import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.shared.domain.LocalizedText;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.UUID;
import com.agilityhub.core.identity.domain.Role;
import com.agilityhub.core.identity.persistence.Membership;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.platform.application.jobs.JobName;
import com.agilityhub.core.platform.application.jobs.JobRunner;
import com.agilityhub.core.platform.application.privacy.ErasureExecutor;
import com.agilityhub.core.platform.application.privacy.ErasureStep;
import com.agilityhub.core.platform.application.privacy.ErasureSubject;
import com.agilityhub.core.platform.persistence.privacy.ErasureRequest;
import com.agilityhub.core.shared.application.OutboxDispatcher;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.support.AuditCovers;
import com.agilityhub.core.support.ConcurrencySupport;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** S14 R-14-14/R-14-15 (E11-T01): erasure requests, their checks and cancellation, and the field-by-field pseudonymisation. */
class ErasureIT extends PrivacyItFixtures {
    static final Instant REQUESTED = Instant.parse("2026-09-05T08:00:00Z"), DUE = Instant.parse("2026-09-30T05:00:00Z");
    @Autowired JobRunner runner; @Autowired OutboxDispatcher dispatcher; @Autowired AttachmentStorage storage;
    @Autowired ErasureExecutor executor; @Autowired List<ErasureStep> steps; @Autowired InstructorWriter instructors;
    /** The stored objects of T-14-08 (fresh keys each run: the local store never overwrites an object). */
    final String photo = java.util.UUID.randomUUID().toString(), card = java.util.UUID.randomUUID().toString(), taskFile = java.util.UUID.randomUUID().toString();

    @BeforeEach void seed() {
        seedClubs();
        clock.setInstant(REQUESTED);
        member(CLUB, "montse", 87, "12345678Z", LocalDate.of(2026, 8, 31));
        member(CLUB, "active", 12, "X1234567L", null);
    }
    ErasureRequest request(String id) { return mongo.findById(id, ErasureRequest.class); }

    /** T-14-19: a public S04 signup with this document, a fictional dog and the club's one plan (`POST /signup`, as a newcomer sends it). */
    JsonNode signupWithDocument(String document, String email) throws Exception {
        var text = new LocalizedText(Map.of("ca", "Quota", "es", "Cuota", "en", "Fee"), "ca");
        mongo.insert(new Plan("plan-rgpd", CLUB, "MONTHLY", text, com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PlanType.MONTHLY,
                com.agilityhub.core.clubs.catalogs.domain.OfferTerms.BillingMode.MONTHLY_FEE, 1, new com.agilityhub.core.clubs.catalogs.domain.OfferTerms.EntryFee(
                com.agilityhub.core.clubs.catalogs.domain.OfferTerms.EntryFeeMode.STANDARD, null, null), null, null, text, null, true, true, 0, true, 0,
                clock.instant(), clock.instant(), null, null));
        mongo.insert(new Price("price-rgpd", CLUB, "plan-rgpd", com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept.MONTHLY_FEE, new Money(6000, "EUR"),
                BigDecimal.ZERO, LocalDate.of(2020, 1, 1), null, 0, clock.instant(), clock.instant(), null, null));
        raw("levels", "_id", "level-a", "clubId", CLUB, "active", true, "name", Map.of("ca", "Iniciació"), "order", 0);
        var request = Map.of("locale", "ca", "website", "", "person", Map.of("idDocument", Map.of("type", "DNI", "value", document), "firstName", "Fictional",
                        "lastName1", "Newcomer", "birthDate", "1990-05-06", "gender", "FEMALE", "emails", List.of(email), "phones", List.of(Map.of("prefix", "+34", "number", "600000009")),
                        "address", Map.of("street", "2 Fictional Street", "postalCode", "99999", "town", "Fictional Town")),
                "dog", Map.of("name", "Fictional Pup", "sex", "FEMALE", "breed", "whippet", "birthMonth", "2024-04", "chip", "941000000100009"), "planId", "plan-rgpd",
                "payment", Map.of("type", "MANUAL", "firstMonthOption", "TODAY"),
                "consents", Map.of("privacyPolicy", Map.of("accepted", true, "version", "v1"), "imageUse", Map.of("granted", false, "version", "v1")));
        return json(mvc.perform(body(post("/api/v1/signup"), request).header("Host", CLUB + ".example.test").header("Idempotency-Key", UUID.randomUUID().toString())), 201);
    }
    long count(String collection, Criteria criteria) { return mongo.count(Query.query(criteria), collection); }

    @Test @AuditCovers({AuditAction.ERASURE_REQUESTED, AuditAction.ERASURE_CANCELLED})
    void T_14_18_R_14_14_aLeftMemberIsScheduledOnceThenCancelledAndN52IsSent() throws Exception {
        var created = json(admin(body(post("/api/v1/members/montse/erasure"), Map.of("reason", "asked by e-mail"))), 201);
        assertThat(created.path("status").asText()).isEqualTo("SCHEDULED");
        assertThat(created.path("scope").asText()).isEqualTo("MEMBER");
        assertThat(created.path("source").asText()).isEqualTo("ADMIN");
        assertThat(created.path("executeAt").asText()).isEqualTo("2026-09-29T22:00:00Z");
        assertThat(created.path("memberIds")).hasSize(1);
        assertThat(created.path("blockReasons")).isEmpty();
        String id = created.path("id").asText();
        assertThat(count("domain_events", Criteria.where("type").is("AccountErasureRequested").and("aggregateId").is(id))).isEqualTo(1);
        var audit = documents("audit_entries", "action", "ERASURE_REQUESTED");
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().getString("memberId")).isEqualTo("montse");
        assertThat(audit.getFirst().getString("reason")).isEqualTo("asked by e-mail");
        dispatcher.dispatch(); dispatcher.dispatch();
        var notice = documents("notifications", "code", "N-52");
        assertThat(notice).hasSize(1);
        assertThat(notice.getFirst().getString("accountId")).isEqualTo("montse-account");
        dispatcher.dispatch();
        assertThat(documents("notifications", "code", "N-52")).hasSize(1);
        // A second request while this one is live.
        assertThat(json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 409).path("code").asText()).isEqualTo("ERASURE_ALREADY_REQUESTED");
        assertThat(json(admin(get("/api/v1/members/montse/erasure")), 200).path("id").asText()).isEqualTo(id);
        json(admin(delete("/api/v1/members/montse/erasure")), 204);
        assertThat(request(id).status()).isEqualTo("CANCELLED");
        assertThat(request(id).cancelledByAccountId()).isEqualTo("admin-account");
        assertThat(documents("audit_entries", "action", "ERASURE_CANCELLED")).hasSize(1);
        assertThat(json(admin(get("/api/v1/members/montse/erasure")), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(json(admin(delete("/api/v1/members/montse/erasure")), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        // After a cancellation the member can be requested again.
        json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 201);
    }

    @Test void T_14_18_R_14_14_eachBlockingReasonRefusesWithNothingCreated() throws Exception {
        raw("invoices", "_id", "invoice-failed", "clubId", CLUB, "memberId", "montse", "status", "FAILED");
        var blocked = json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 422);
        assertThat(blocked.path("code").asText()).isEqualTo("ERASURE_BLOCKED");
        assertThat(blocked.path("details").path("reasons").toString()).isEqualTo("[\"INVOICES_OPEN\"]");
        mongo.remove(new Query(), "invoices");
        raw("upfront_payments", "_id", "upfront-due", "clubId", CLUB, "memberId", "montse", "status", "DUE");
        raw("bookings", "_id", "booking-future", "clubId", CLUB, "memberId", "montse", "state", "ACTIVE", "classStartsAt", Instant.parse("2026-09-10T18:00:00Z"));
        raw("training_bookings", "_id", "training-past", "clubId", CLUB, "memberId", "montse", "state", "ACTIVE", "startsAt", Instant.parse("2026-08-10T18:00:00Z"));
        assertThat(json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 422).path("details").path("reasons").toString())
                .isEqualTo("[\"FUTURE_BOOKINGS\",\"UPFRONT_OPEN\"]");
        assertThat(json(admin(body(post("/api/v1/members/active/erasure"), Map.of())), 422).path("details").path("reasons").toString())
                .isEqualTo("[\"MEMBER_NOT_LEFT\"]");
        assertThat(mongo.count(new Query(), "erasure_requests")).isZero();
        assertThat(count("audit_entries", Criteria.where("action").is("ERASURE_REQUESTED"))).isZero();
    }

    @Test void T_14_22_R_14_14_anotherClubIsNotFoundAndOnlyAnAdminRequests() throws Exception {
        assertThat(json(call(body(post("/api/v1/members/montse/erasure"), Map.of()), OTHER, "other-admin-account", "ADMIN"), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(json(call(get("/api/v1/members/montse/erasure"), OTHER, "other-admin-account", "ADMIN"), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(json(call(body(post("/api/v1/members/montse/erasure"), Map.of()), CLUB, "active-account", "MEMBER"), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(call(body(post("/api/v1/members/montse/erasure"), Map.of()), CLUB, "active-account", "INSTRUCTOR"), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(mongo.count(new Query(), "erasure_requests")).isZero();
    }

    @Test void T_14_24_R_14_14_twoSimultaneousRequestsGiveOneCreatedAndOneConflict() throws Exception {
        var statuses = ConcurrencySupport.parallel(2, ignored -> () -> admin(body(post("/api/v1/members/montse/erasure"), Map.of())).andReturn().getResponse().getStatus());
        assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        assertThat(mongo.count(new Query(), "erasure_requests")).isEqualTo(1);
        // Two executions of the same request at once erase it once.
        String id = mongo.findOne(new Query(), ErasureRequest.class).id();
        clock.setInstant(DUE);
        var outcomes = ConcurrencySupport.parallel(2, ignored -> () -> { try (var tenant = TenantContext.open(CLUB)) { return executor.execute(id); } });
        assertThat(outcomes).containsExactlyInAnyOrder(ErasureExecutor.Outcome.ERASED, ErasureExecutor.Outcome.SKIPPED);
        assertThat(count("audit_entries", Criteria.where("action").is("MEMBER_ERASED"))).isEqualTo(1);
        assertThat(count("domain_events", Criteria.where("type").is("AccountErased"))).isEqualTo(1);
    }

    /** Montse's three stored files in the club: Kiwi's photo, its vaccination card and a task's attachment. */
    void seedFiles() throws Exception {
        storage.put(photo, "image/jpeg", 3, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        storage.put(card, "application/pdf", 3, new ByteArrayInputStream(new byte[] {4, 5, 6}));
        storage.put(taskFile, "image/png", 3, new ByteArrayInputStream(new byte[] {7, 8, 9}));
        dog(CLUB, "kiwi", "montse", "941000000100001", photo);
        raw("dog_documents", "_id", "doc-kiwi", "clubId", CLUB, "dogId", "kiwi", "type", "VACCINATION", "state", "VALID", "version", 0,
                "files", List.of(map("id", "f1", "fileKey", card, "name", "cartilla-kiwi.pdf", "mimeType", "application/pdf")));
        raw("tasks", "_id", "task-1", "clubId", CLUB, "memberId", "montse", "dogId", "kiwi", "text", "practise the weaves", "state", "OPEN",
                "createdBy", map("accountId", "instructor-account", "displayName", "Fictional Instructor"), "doneBy", map("accountId", "montse-account", "displayName", "Fictional Montse"));
        raw("attachments", "_id", "attachment-1", "clubId", CLUB, "entityType", "TASK", "entityId", "task-1", "fileKey", taskFile, "name", "kiwi.png", "version", 0L);
    }
    boolean stored(String key) {
        try { storage.read(key); return true; } catch (Exception missing) { return false; }
    }

    /** T-14-08's seed: Montse with two dogs, invoices, bookings, tasks, notices and audit, in every collection R-14-15 lists. */
    void seedEverything() throws Exception {
        seedFiles();
        dog(CLUB, "blat", "montse", "941000000100002", null);
        dog(CLUB, "other-dog", "active", "941000000100003", null);
        raw("inactivity_periods", "_id", "inactivity-1", "clubId", CLUB, "memberId", "montse", "fromMonth", "2026-03", "toMonth", "2026-04", "comments", "a personal comment",
                "decision", map("at", Instant.parse("2026-02-20T10:00:00Z"), "byAccountId", "admin-account", "decision", "APPROVED", "note", "admin note", "deadlineOverridden", false),
                "state", "FINISHED");
        raw("leave_requests", "_id", "leave-1", "clubId", CLUB, "memberId", "montse", "comment", "leaving because", "nps", 9, "reasonKey", "MOVING", "state", "APPROVED");
        raw("bookings", "_id", "booking-1", "clubId", CLUB, "memberId", "montse", "dogId", "kiwi", "state", "CANCELLED", "classStartsAt", Instant.parse("2026-07-01T18:00:00Z"),
                "cancelMessage", "club text naming Montse", "bookedBy", map("accountId", "montse-account", "displayName", "Fictional Montse"),
                "cancelledBy", map("accountId", "admin-account", "displayName", "Fictional Admin"));
        raw("training_bookings", "_id", "training-1", "clubId", CLUB, "memberId", "montse", "dogId", "blat", "state", "CANCELLED", "startsAt", Instant.parse("2026-07-02T18:00:00Z"), "cancelNote", "a note");
        // Montse once marked a class as a helper: her name as marker goes with her account; the admin's stays.
        raw("attendances", "_id", "attendance-1", "clubId", CLUB, "bookingId", "booking-other", "memberId", "active", "state", "PRESENT",
                "markedBy", map("accountId", "montse-account", "role", "INSTRUCTOR", "displayName", "Fictional Montse"));
        raw("attendances", "_id", "attendance-2", "clubId", CLUB, "bookingId", "booking-1", "memberId", "montse", "state", "PRESENT",
                "markedBy", map("accountId", "admin-account", "role", "ADMIN", "displayName", "Fictional Admin"));
        raw("activity_registrations", "_id", "registration-1", "clubId", CLUB, "memberId", "montse", "state", "CANCELLED",
                "registeredBy", map("accountId", "montse-account", "displayName", "Fictional Montse"));
        raw("followup_items", "_id", "item-1", "clubId", CLUB, "memberId", "montse", "dogId", "kiwi", "taskId", "task-1", "textExcerpt", "practise the weaves",
                "authorAccountId", "montse-account", "authorName", "Fictional Montse");
        // The invoice and its collection as S12 writes them, so that `GET /invoices/{id}` reads them back (T-14-19).
        Money fee = new Money(9000, "EUR"), zero = new Money(0, "EUR");
        mongo.insert(new Invoice("invoice-1", CLUB, "2026", 744, "2026-0744", "2026-07-25", "2026-08", "montse",
                new Invoice.MemberSnapshot(87, "Fictional Montse Person", "12345678Z"), List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-a", null,
                "Quota", fee, BigDecimal.ZERO, zero, fee)), fee, zero, fee,
                new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "···· 1332", "Fictional Holder", "rgpd-87-1", null, null),
                InvoiceStatus.PAID, InvoiceKind.PERIODIC, null, null, false, null, Instant.parse("2026-08-05T10:00:00Z"), null, null, null, null, zero, null, 0L,
                Instant.parse("2026-07-25T05:00:00Z"), "admin-account", Instant.parse("2026-08-05T10:00:00Z"), "admin-account"));
        mongo.insert(new Collection("collection-1", CLUB, "invoice-1", CollectionProvider.SEPA_XML, fee, CollectionStatus.SUCCEEDED, "provider-ref-1", null, 1,
                null, null, List.of(), Instant.parse("2026-08-01T05:00:00Z"), Instant.parse("2026-08-05T10:00:00Z")));
        raw("notifications", "_id", "notification-1", "clubId", CLUB, "code", "N-13", "title", "Hola Montse", "body", "Kiwi …", "smsBody", "sms", "variables", map("member_name", "Montse"),
                "recipient", map("accountId", "montse-account", "memberId", "montse", "displayName", "Fictional Montse", "email", "montse@example.test"),
                "recipientEmail", "montse@example.test", "accountId", "montse-account", "channel", "EMAIL", "status", "SENT", "sentAt", Instant.parse("2026-07-01T10:00:00Z"),
                "deliveries", List.of(map("channel", "EMAIL", "target", "montse@example.test", "status", "SENT")));
        raw("announcements", "_id", "announcement-1", "clubId", CLUB, "templateId", "t", "selection", "MEMBERS", "q", "montse", "memberIds", List.of("montse", "active"), "recipientCount", 2);
        raw("push_subscriptions", "_id", "push-1", "clubId", CLUB, "accountId", "montse-account", "endpoint", "https://push.example.test/1", "status", "ACTIVE");
        raw("audit_entries", "_id", "audit-1", "clubId", CLUB, "at", Instant.parse("2026-07-01T10:00:00Z"), "actorAccountId", "admin-account", "actorName", "Fictional Admin",
                "actorRole", "ADMIN", "action", "MEMBER_PAYMENT_METHOD_CHANGED", "entityType", "Member", "entityId", "montse", "memberId", "montse",
                "changes", List.of(map("path", "paymentMethod.iban", "before", "···· 0000", "after", "···· 1332")), "reason", "asked", "ip", "192.0.2.10", "userAgent", "Fictional Browser",
                "traceId", "trace-1", "origin", "BACKOFFICE", "details", map("note", "Montse"));
        raw("audit_entries", "_id", "audit-own", "clubId", OTHER, "at", Instant.parse("2026-07-01T10:00:00Z"), "actorAccountId", "montse-account", "actorName", "Fictional Montse",
                "actorRole", "MEMBER", "action", "DOG_UPDATED", "entityType", "Dog", "entityId", "x", "changes", List.of(map("path", "name", "before", "a", "after", "b")),
                "traceId", "trace-2", "origin", "APP", "ip", "192.0.2.11");
        raw("saved_views", "_id", "view-1", "clubId", CLUB, "data", map("ownerAccountId", "montse-account", "listKey", "dogs", "name", "Mine"));
        raw("refresh_tokens", "_id", "refresh-1", "clubId", CLUB, "accountId", "montse-account", "tokenHash", "hash");
        raw("security_events", "_id", "security-1", "at", Instant.parse("2026-07-01T10:00:00Z"), "type", "LOGIN_FAILED", "accountId", "montse-account", "route", "/oauth2/token");
        raw("courses", "_id", "course-1", "ownerType", "ACCOUNT", "ownerAccountId", "montse-account", "visibility", "PUBLIC", "designerName", "Fictional Montse");
        raw("domain_events", "_id", "event-1", "clubId", CLUB, "type", "MemberUpdated", "aggregateId", "montse", "status", "PUBLISHED", "payload", map("memberId", "montse", "diff", map("firstName", "Montse")));
        raw("domain_events", "_id", "event-pending", "clubId", CLUB, "type", "MemberUpdated", "aggregateId", "montse", "status", "PENDING", "payload", map("memberId", "montse"));
    }

    @Test @AuditCovers({AuditAction.MEMBER_ERASED, AuditAction.ACCOUNT_ERASED})
    void T_14_08_T_14_19_R_14_15_eachFieldOfTheTableIsPseudonymisedAndTheFilesLeaveTheStore() throws Exception {
        seedEverything();
        String id = json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 201).path("id").asText();
        clock.setInstant(DUE);
        var run = runner.manual(CLUB, JobName.ERASURES, false, "admin-account");
        assertThat(run.errors()).isEmpty();
        assertThat(run.items()).extracting(item -> item.action()).containsExactly("ERASED");
        var request = request(id);
        assertThat(request.status()).isEqualTo("ERASING");
        assertThat(request.accountErased()).isTrue();
        assertThat(request.filesPending()).containsExactlyInAnyOrder("attachments:" + photo, "attachments:" + card,
                "attachments:" + taskFile);
        // Member (R-14-15): placeholders, nothing personal; number, status, dates, plan, consents and mandate kept.
        var member = document("members", "montse");
        assertThat(member.getString("firstName")).isEqualTo("Abonat suprimit");
        assertThat(member.getString("lastName1")).isEqualTo("#87");
        assertThat(member).doesNotContainKeys("lastName2", "birthDate", "address", "remarks", "internalNotes", "notificationPreferences", "readmissionRequest",
                "lastDogForClass", "lastDogForTraining");
        assertThat(member.getList("contactEmails", Object.class)).isEmpty();
        assertThat(member.getList("phones", Object.class)).isEmpty();
        assertThat(member.get("idDocument", Document.class)).containsEntry("type", "ERASED").containsEntry("number", "ERASED-montse");
        assertThat(member.get("paymentMethod", Document.class)).containsOnlyKeys("type", "mandateRef", "mandateSignedAt").containsEntry("mandateRef", "rgpd-87-1");
        assertThat(member.get("signup", Document.class)).doesNotContainKey("userAgent").containsEntry("ipHash", "hash-montse");
        assertThat(member.get("familyGroupClaim", Document.class)).doesNotContainKeys("holderName", "dogName");
        assertThat(member.get("bookingBlock", Document.class)).doesNotContainKey("reason");
        assertThat(member.getInteger("memberNumber")).isEqualTo(87);
        assertThat(member.getString("status")).isEqualTo("LEFT");
        assertThat(member.getString("gender")).isEqualTo("FEMALE");
        assertThat(member.getString("leaveDate")).isEqualTo("2026-08-31");
        assertThat(member.getString("planId")).isEqualTo("plan-a");
        assertThat(member.getList("consents", Object.class)).hasSize(2);
        assertThat(member.get("erasedAt")).isNotNull();
        assertThat(member.getString("erasureRequestId")).isEqualTo(id);
        assertThat(member.toJson()).doesNotContain("montse@example.test", IBAN, "Fictional Holder", "12345678Z", "Fictional Browser", "Remarks about");
        // Dogs: «Gos suprimit», no chip, licences, notes, handler or photo; breed, sex, birth date, level and history kept.
        for (String dogId : List.of("kiwi", "blat")) {
            var dog = document("dogs", dogId);
            assertThat(dog.getString("name")).isEqualTo("Gos suprimit");
            assertThat(dog).doesNotContainKeys("chip", "instructorNote", "remarks", "photoFileKey", "handlerName");
            assertThat(dog.getList("licenses", Object.class)).isEmpty();
            assertThat(dog.getString("breed")).isEqualTo("whippet");
            assertThat(dog.getString("levelId")).isEqualTo("level-a");
            assertThat(dog.getList("levelHistory", Object.class)).hasSize(1);
            assertThat(dog.get("erasedAt")).isNotNull();
        }
        assertThat(document("dogs", "other-dog").getString("chip")).isEqualTo("941000000100003");
        var file = document("dog_documents", "doc-kiwi").getList("files", Document.class).getFirst();
        assertThat(file.get("name")).isNull(); assertThat(file.get("removedAt")).isNotNull();
        assertThat(document("inactivity_periods", "inactivity-1")).doesNotContainKeys("comments");
        assertThat(document("inactivity_periods", "inactivity-1").get("decision", Document.class)).doesNotContainKey("note").containsEntry("decision", "APPROVED");
        assertThat(document("leave_requests", "leave-1")).doesNotContainKey("comment").containsEntry("nps", 9).containsEntry("reasonKey", "MOVING");
        // Bookings, training, activities: ids, states and dates kept; the club's text and the member's own name go.
        var booking = document("bookings", "booking-1");
        assertThat(booking).doesNotContainKey("cancelMessage");
        assertThat(booking.get("bookedBy", Document.class)).doesNotContainKey("displayName");
        assertThat(booking.get("cancelledBy", Document.class)).containsEntry("displayName", "Fictional Admin");
        assertThat(document("training_bookings", "training-1")).doesNotContainKey("cancelNote");
        assertThat(document("attendances", "attendance-1").get("markedBy", Document.class)).doesNotContainKey("displayName").containsEntry("accountId", "montse-account");
        assertThat(document("attendances", "attendance-2").get("markedBy", Document.class)).containsEntry("displayName", "Fictional Admin");
        assertThat(document("activity_registrations", "registration-1").get("registeredBy", Document.class)).doesNotContainKey("displayName");
        // Follow-up: «(suprimit)», the attachments named no more.
        var task = document("tasks", "task-1");
        assertThat(task.getString("text")).isEqualTo("(suprimit)");
        assertThat(task.get("doneBy", Document.class)).doesNotContainKey("displayName");
        assertThat(task.get("createdBy", Document.class)).containsEntry("displayName", "Fictional Instructor");
        assertThat(document("attachments", "attachment-1")).doesNotContainKey("name").containsKey("removedAt");
        assertThat(document("followup_items", "item-1")).containsEntry("textExcerpt", "(suprimit)").doesNotContainKey("authorName");
        // Invoices: number, lines, amounts and state kept; the snapshot names «Abonat suprimit #87».
        var invoice = document("invoices", "invoice-1");
        assertThat(invoice.getString("displayNumber")).isEqualTo("2026-0744");
        assertThat(invoice.getString("status")).isEqualTo("PAID");
        assertThat(invoice.get("total", Document.class)).containsEntry("amountMinor", 9000L);
        assertThat(invoice.get("paymentMethod", Document.class)).containsEntry("holderName", "Abonat suprimit #87").doesNotContainKey("maskedAccount");
        assertThat(invoice.get("memberSnapshot", Document.class)).containsEntry("fullName", "Abonat suprimit #87").doesNotContainKey("taxId").containsEntry("number", 87);
        assertThat(document("collections", "collection-1")).containsEntry("providerRef", "provider-ref-1");
        // Messaging: no content, the account's pseudonym as address; announcements forget the member; push subscriptions go.
        var notification = document("notifications", "notification-1");
        assertThat(notification).doesNotContainKeys("title", "body", "smsBody", "variables").containsEntry("code", "N-13").containsEntry("status", "SENT");
        String pseudonym = document("accounts", "montse-account").getString("email");
        assertThat(pseudonym).matches("erased\\+[0-9a-f]{16}@erased\\.invalid");
        assertThat(notification.getString("recipientEmail")).isEqualTo(pseudonym);
        assertThat(notification.get("recipient", Document.class)).containsEntry("email", pseudonym).doesNotContainKey("displayName");
        assertThat(notification.getList("deliveries", Document.class).getFirst()).doesNotContainKey("target");
        var announcement = document("announcements", "announcement-1");
        assertThat(announcement.getList("memberIds", String.class)).containsExactly("active");
        assertThat(announcement).doesNotContainKey("q");
        assertThat(count("push_subscriptions", Criteria.where("accountId").is("montse-account"))).isZero();
        // Audit: action, entity, ids and date only — here and, for the account's own entries, in the other club.
        var entry = document("audit_entries", "audit-1");
        assertThat(entry.getList("changes", Object.class)).isEmpty();
        assertThat(entry).doesNotContainKeys("reason", "actorName", "ip", "userAgent", "details").containsEntry("action", "MEMBER_PAYMENT_METHOD_CHANGED")
                .containsEntry("memberId", "montse").containsEntry("entityId", "montse");
        assertThat(document("audit_entries", "audit-own")).doesNotContainKeys("actorName", "ip").containsEntry("actorAccountId", "montse-account");
        assertThat(documents("audit_entries", "action", "MEMBER_ERASED")).singleElement()
                .satisfies(erased -> assertThat(erased).containsEntry("memberId", "montse").containsEntry("actorRole", "SYSTEM").doesNotContainKey("actorName"));
        assertThat(documents("audit_entries", "action", "ACCOUNT_ERASED")).singleElement().satisfies(erased -> assertThat(erased).containsEntry("entityId", "montse-account"));
        // Account and identity: pseudonym, «Compte suprimit», ERASED; tokens, saved views, security events gone; courses private.
        var account = document("accounts", "montse-account");
        assertThat(account.getString("name")).isEqualTo("Compte suprimit");
        assertThat(account.getString("status")).isEqualTo("ERASED");
        assertThat(account).doesNotContainKeys("passwordHash", "emailVerifiedAt");
        assertThat(document("memberships", "montse-account-" + CLUB)).containsEntry("status", "ERASED").containsEntry("roles", List.of());
        assertThat(count("refresh_tokens", Criteria.where("accountId").is("montse-account"))).isZero();
        assertThat(count("saved_views", Criteria.where("data.ownerAccountId").is("montse-account"))).isZero();
        assertThat(count("security_events", Criteria.where("accountId").is("montse-account"))).isZero();
        assertThat(document("courses", "course-1")).containsEntry("visibility", "PRIVATE").doesNotContainKey("designerName");
        assertThat(mongo.findById("event-1", Document.class, "domain_events")).isNull();
        assertThat(mongo.findById("event-pending", Document.class, "domain_events")).isNotNull();
        // The other member is untouched.
        assertThat(document("members", "active").getString("firstName")).isEqualTo("Fictional");
        // AccountErased → the files leave the store → ERASED.
        assertThat(count("domain_events", Criteria.where("type").is("AccountErased").and("payload.accountId").is("montse-account"))).isEqualTo(1);
        dispatcher.dispatch(); dispatcher.dispatch();
        var closed = request(id);
        assertThat(closed.status()).isEqualTo("ERASED");
        assertThat(closed.erasedAt()).isEqualTo(DUE);
        assertThat(closed.filesPending()).isEmpty();
        for (String key : List.of(photo, card, taskFile)) {
            assertThatThrownBy(() -> storage.read(key)).as(key).isNotNull();
        }
        // An erased member cannot be changed any more (S14 §5), and the census shows «suprimit».
        assertThat(json(admin(body(patch("/api/v1/members/montse"), Map.of("remarks", "x"))), 409).path("code").asText()).isEqualTo("MEMBER_ERASED");
        assertThat(json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 409).path("code").asText()).isEqualTo("MEMBER_ERASED");
        // INC-53 point 10: the ADMIN view sends the erased birth date and address as null (both nullable in Member); an erased SEPA
        // member has no missing account to ask for; the fiche still lists the dogs, pseudonymised; the preferences (removed), the
        // package and the impersonation token answer MEMBER_ERASED, as their contracts declare.
        var erased = json(admin(get("/api/v1/members/montse")), 200);
        assertThat(erased.has("birthDate") && erased.path("birthDate").isNull()).as(erased.toString()).isTrue();
        assertThat(erased.has("address") && erased.path("address").isNull()).as(erased.toString()).isTrue();
        assertThat(erased.path("accountMissing").asBoolean()).isFalse();
        assertThat(erased.at("/paymentMethod/type").asText()).isEqualTo("SEPA_DD");
        assertThat(erased.at("/displayStatus/kind").asText()).isEqualTo("ERASED");
        assertThat(json(call(get("/api/v1/members/montse"), CLUB, "instructor-account", "INSTRUCTOR"), 200).has("address")).isFalse();
        var overview = json(admin(get("/api/v1/members/montse/overview")), 200);
        assertThat(overview.path("dogs").findValuesAsText("name")).containsOnly("Gos suprimit");
        assertThat(overview.path("dogs")).hasSize(2);
        assertThat(json(admin(get("/api/v1/members/montse/notification-preferences")), 409).path("code").asText()).isEqualTo("MEMBER_ERASED");
        assertThat(json(admin(body(post("/api/v1/members/montse/data-export"), Map.of("deliverToMember", false))), 409).path("code").asText()).isEqualTo("MEMBER_ERASED");
        assertThat(json(admin(body(post("/api/v1/members/montse/impersonation-token"), Map.of("reason", "support"))), 409).path("code").asText()).isEqualTo("MEMBER_ERASED");
        // T-14-19 (E11-T01 round 2): the receipt still reads, anonymised — D6 «2026-0744 · 90 € · cobrat · Abonat suprimit #87».
        var receipt = json(admin(get("/api/v1/invoices/invoice-1")), 200);
        assertThat(receipt.path("displayNumber").asText()).isEqualTo("2026-0744");
        assertThat(receipt.path("status").asText()).isEqualTo("PAID");
        assertThat(receipt.at("/total/amountMinor").asLong()).isEqualTo(9000);
        assertThat(receipt.at("/memberSnapshot/fullName").asText()).isEqualTo("Abonat suprimit #87");
        assertThat(receipt.at("/memberSnapshot/number").asInt()).isEqualTo(87);
        assertThat(receipt.at("/paymentMethod/holderName").asText()).isEqualTo("Abonat suprimit #87");
        assertThat(receipt.at("/paymentMethod/mandateRef").asText()).isEqualTo("rgpd-87-1");
        assertThat(receipt.path("collections")).hasSize(1);
        assertThat(receipt.toString()).doesNotContain("Fictional", "12345678Z", "1332");
        // T-14-19: S04 with Montse's old DNI finds nobody to readmit (R-14-14 last sentence): a new member, a new account, a new number.
        var signup = signupWithDocument("12345678Z", "montse@example.test");
        String newcomer = signup.path("memberId").asText();
        assertThat(newcomer).isNotEqualTo("montse");
        var fresh = document("members", newcomer);
        assertThat(fresh.getString("status")).isEqualTo("PENDING");
        assertThat(fresh.get("signup", Document.class).getBoolean("readmission")).isFalse();
        assertThat(fresh.get("idDocument", Document.class)).containsEntry("number", "12345678Z");
        String newcomerDog = documents("dogs", "memberId", newcomer).getFirst().getString("_id");
        var validated = json(admin(body(post("/api/v1/members/" + newcomer + "/validation"), Map.of("version", ((Number) fresh.get("version")).longValue(),
                "dogs", List.of(Map.of("dogId", newcomerDog, "levelId", "level-a")), "nextInvoiceDate", "2026-11-01",
                "upfrontAmountPaid", Map.of("amountMinor", signup.at("/upfront/totalDue/amountMinor").asLong(), "currency", "EUR")))), 200);
        assertThat(validated.path("number").asInt()).isPositive().isNotEqualTo(87);
        assertThat(document("members", newcomer).getString("accountId")).isNotEqualTo("montse-account");
        assertThat(document("members", "montse").getString("firstName")).isEqualTo("Abonat suprimit");
        assertThat(document("members", "montse").get("idDocument", Document.class)).containsEntry("number", "ERASED-montse");
        // R-14-15 is idempotent: running every step again changes nothing.
        var before = List.of(document("members", "montse").toJson(), document("dogs", "kiwi").toJson(), document("invoices", "invoice-1").toJson(),
                document("notifications", "notification-1").toJson(), document("tasks", "task-1").toJson(), document("audit_entries", "audit-1").toJson());
        try (var tenant = TenantContext.open(CLUB)) {
            var subject = new ErasureSubject(id, CLUB, List.of(new com.agilityhub.core.platform.application.privacy.ErasureMember("montse", "montse-account", 87, "LEFT",
                    LocalDate.of(2026, 8, 31), null, "LEAVE_REQUEST", null, List.of("kiwi", "blat"))), java.util.Set.of("montse-account"),
                    Map.of("montse-account", pseudonym), new com.agilityhub.core.platform.application.privacy.ErasurePlaceholders("Abonat suprimit", "Compte suprimit",
                    "Gos suprimit", "(suprimit)"), DUE);
            steps.forEach(step -> step.erase(subject));
        }
        assertThat(List.of(document("members", "montse").toJson(), document("dogs", "kiwi").toJson(), document("invoices", "invoice-1").toJson(),
                document("notifications", "notification-1").toJson(), document("tasks", "task-1").toJson(), document("audit_entries", "audit-1").toJson())).isEqualTo(before);
    }

    @Test void T_14_19_R_14_15_anAccountWithAnActiveMembershipElsewhereKeepsItsAccount() throws Exception {
        membership("montse-account", OTHER, "montse-other", Membership.Status.ACTIVE, Role.MEMBER);
        String id = json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 201).path("id").asText();
        clock.setInstant(DUE);
        runner.manual(CLUB, JobName.ERASURES, false, "admin-account");
        assertThat(request(id).status()).isEqualTo("ERASING");
        assertThat(request(id).accountErased()).isFalse();
        var account = document("accounts", "montse-account");
        assertThat(account.getString("email")).isEqualTo("montse@example.test");
        assertThat(account.getString("status")).isEqualTo("ACTIVE");
        assertThat(document("memberships", "montse-account-" + CLUB)).containsEntry("status", "ERASED");
        assertThat(document("memberships", "montse-account-" + OTHER)).containsEntry("status", "ACTIVE");
        assertThat(count("domain_events", Criteria.where("type").is("AccountErased").and("payload.accountId").exists(false))).isEqualTo(1);
        assertThat(count("audit_entries", Criteria.where("action").is("ACCOUNT_ERASED"))).isZero();
        dispatcher.dispatch();
        assertThat(request(id).status()).isEqualTo("ERASED");
    }

    /**
     * R-14-15's exception (E11-T01 round 2): the account stays while another club's membership is not `ERASED`, a `SUSPENDED` one
     * (a former member there) too, so that club's member never points at an erased account.
     */
    @Test void T_14_19_R_14_15_aSuspendedMembershipElsewhereAlsoKeepsTheAccount() throws Exception {
        membership("montse-account", OTHER, "montse-other", Membership.Status.SUSPENDED);
        String id = json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 201).path("id").asText();
        clock.setInstant(DUE);
        runner.manual(CLUB, JobName.ERASURES, false, "admin-account");
        assertThat(request(id).status()).isEqualTo("ERASING");
        assertThat(request(id).accountErased()).isFalse();
        var account = document("accounts", "montse-account");
        assertThat(account.getString("email")).isEqualTo("montse@example.test");
        assertThat(account.getString("status")).isEqualTo("ACTIVE");
        assertThat(document("memberships", "montse-account-" + CLUB)).containsEntry("status", "ERASED");
        assertThat(document("memberships", "montse-account-" + OTHER)).containsEntry("status", "SUSPENDED");
        assertThat(document("members", "montse").getString("firstName")).isEqualTo("Abonat suprimit");
        assertThat(count("audit_entries", Criteria.where("action").is("ACCOUNT_ERASED"))).isZero();
    }

    /**
     * S14 §5 «plataforma» (E11-T01 round 2): an account request blocked halfway is cancelled from the console. The club it already
     * erased stays erased; the blocked club's member is left as it is, and no daily run touches it again. E11-T11: the erased club's
     * files (a dog photo, a document and a task attachment) leave the store when its part runs, not when the request completes, and
     * a file a failed cleanup left pending on the cancelled request goes at the next daily run.
     */
    @Test @AuditCovers(AuditAction.ERASURE_CANCELLED)
    void R_14_14_thePlatformCancelsAnAccountRequestBlockedHalfway() throws Exception {
        seedFiles();
        member(OTHER, "montse-b", 3, "87654321X", LocalDate.of(2026, 8, 1));
        mongo.remove(Query.query(Criteria.where("accountId").is("montse-b-account")), "memberships");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("montse-b")), new org.springframework.data.mongodb.core.query.Update().set("accountId", "montse-account"), "members");
        membership("montse-account", OTHER, "montse-b", Membership.Status.SUSPENDED);
        String id = json(platform(body(post("/api/v1/accounts/montse-account/erasure"), Map.of())), 201).path("id").asText();
        assertThat(List.of(photo, card, taskFile)).allSatisfy(key -> assertThat(stored(key)).as("before: " + key).isTrue());
        clock.setInstant(DUE);
        runner.manual(CLUB, JobName.ERASURES, false, "admin-account");
        assertThat(request(id).status()).isEqualTo("SCHEDULED");
        assertThat(request(id).filesPending()).isEmpty();
        assertThat(List.of(photo, card, taskFile)).allSatisfy(key -> assertThat(stored(key)).as("after club A's part: " + key).isFalse());
        raw("invoices", "_id", "invoice-open-b", "clubId", OTHER, "memberId", "montse-b", "status", "FAILED");
        runner.manual(OTHER, JobName.ERASURES, false, "other-admin-account");
        assertThat(request(id).status()).isEqualTo("BLOCKED");
        assertThat(request(id).erasedClubIds()).containsExactly(CLUB);
        // Only the platform cancels it; a club's member request is not the platform's to cancel.
        assertThat(json(admin(delete("/api/v1/platform/erasure-requests/" + id)), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(call(delete("/api/v1/platform/erasure-requests/" + id), OTHER, "other-admin-account", "ADMIN"), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(mvc.perform(delete("/api/v1/platform/erasure-requests/" + id).header("Host", "unknown.example.test").with(
                org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt().jwt(j -> j.subject("platform-account").claim("imp", true))
                        .authorities(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_AGILITYHUB_ADMIN")))).andReturn().getResponse().getStatus())
                .isEqualTo(403);
        assertThat(request(id).status()).isEqualTo("BLOCKED");
        json(platform(delete("/api/v1/platform/erasure-requests/" + id)), 204);
        var cancelled = request(id);
        assertThat(cancelled.status()).isEqualTo("CANCELLED");
        assertThat(cancelled.cancelledByAccountId()).isEqualTo("platform-account");
        assertThat(cancelled.cancelledAt()).isEqualTo(DUE);
        assertThat(cancelled.erasedClubIds()).containsExactly(CLUB);
        assertThat(documents("audit_entries", "action", "ERASURE_CANCELLED")).singleElement()
                .satisfies(entry -> { assertThat(entry).containsEntry("entityId", id); assertThat(entry.get("clubId")).isNull(); });
        assertThat(document("members", "montse").getString("firstName")).isEqualTo("Abonat suprimit");
        assertThat(document("members", "montse-b").getString("firstName")).isEqualTo("Fictional");
        assertThat(document("accounts", "montse-account").getString("status")).isEqualTo("ACTIVE");
        assertThat(List.of(photo, card, taskFile)).allSatisfy(key -> assertThat(stored(key)).as("after the cancel: " + key).isFalse());
        // Closed: a second cancel, an unknown id and a club's member request are refused; the daily run leaves the request alone.
        assertThat(json(platform(delete("/api/v1/platform/erasure-requests/" + id)), 409).path("details").path("status").asText()).isEqualTo("CANCELLED");
        assertThat(json(platform(delete("/api/v1/platform/erasure-requests/nobody")), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        mongo.remove(Query.query(Criteria.where("_id").is("invoice-open-b")), "invoices");
        // A file whose cleanup failed stays pending on the cancelled request: the next daily run deletes it, and the status stays.
        String left = UUID.randomUUID().toString();
        storage.put(left, "image/jpeg", 3, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        mongo.updateFirst(Query.query(Criteria.where("_id").is(id)), new org.springframework.data.mongodb.core.query.Update().push("filesPending", "attachments:" + left), ErasureRequest.class);
        runner.manual(OTHER, JobName.ERASURES, false, "other-admin-account");
        assertThat(stored(left)).isFalse();
        assertThat(request(id).filesPending()).isEmpty();
        assertThat(request(id).status()).isEqualTo("CANCELLED");
        assertThat(document("members", "montse-b").getString("firstName")).isEqualTo("Fictional");
        String member = json(call(body(post("/api/v1/members/montse-b/erasure"), Map.of()), OTHER, "other-admin-account", "ADMIN"), 201).path("id").asText();
        var refused = json(platform(delete("/api/v1/platform/erasure-requests/" + member)), 409);
        assertThat(refused.path("code").asText()).isEqualTo("INVALID_STATE");
        assertThat(refused.path("details").path("scope").asText()).isEqualTo("MEMBER");
    }

    /**
     * R-14-15 (E11-T01 round 2, question 3): an erased member who is an instructor keeps the profile, named «Abonat suprimit #87»,
     * and the attendance summaries it saved (`savedByAccountId`) name it the same way. A summary saved before the account was stored
     * has nothing to match and keeps its name.
     */
    @Test void R_14_15_anErasedInstructorsNameLeavesTheCatalogAndTheAttendanceSummaries() throws Exception {
        Instant created = Instant.parse("2025-01-01T00:00:00Z");
        // Written as S05 writes them (E11-T11): each write leaves an InstructorChanged whose diff carries the short name.
        try (var tenant = TenantContext.open(CLUB)) {
            var first = new Instructor("instructor-montse", CLUB, "montse", "Montse R", "#ff7a00", true, 2L, created, created, "admin-account", "admin-account");
            instructors.save(first.id(), first.memberId(), Map.of(), first);
            instructors.save(first.id(), first.memberId(), instructors.fields(first),
                    new Instructor("instructor-montse", CLUB, "montse", "Montse", "#ff7a00", false, 3L, created, created, "admin-account", "admin-account"));
            var neus = new Instructor("instructor-neus", CLUB, "active", "Neus", "#0057b8", true, 1L, created, created, "admin-account", "admin-account");
            instructors.save(neus.id(), neus.memberId(), Map.of(), neus);
        }
        dispatcher.dispatch(); dispatcher.dispatch();
        assertThat(count("domain_events", Criteria.where("type").is("InstructorChanged").and("aggregateId").is("instructor-montse").and("status").is("PUBLISHED")))
                .isEqualTo(2);
        for (var row : List.of(List.of("class-montse", "Montse", "montse-account"), List.of("class-neus", "Neus", "active-account"))) {
            raw("class_sessions", "_id", row.get(0), "clubId", CLUB, "state", "FINISHED", "instructorIds", List.of("instructor-montse"),
                    "attendanceSummary", map("version", 2, "marked", 3, "present", 2, "notified", 0, "noShow", 1, "notifiedAfterEnd", 0,
                            "savedAt", Instant.parse("2026-07-01T20:00:00Z"), "savedByName", row.get(1), "savedByAccountId", row.get(2)));
        }
        raw("class_sessions", "_id", "class-before", "clubId", CLUB, "state", "FINISHED", "attendanceSummary", map("version", 1, "marked", 1, "present", 1,
                "notified", 0, "noShow", 0, "savedAt", Instant.parse("2025-07-01T20:00:00Z"), "savedByName", "Montse"));
        json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 201);
        clock.setInstant(DUE);
        runner.manual(CLUB, JobName.ERASURES, false, "admin-account");
        var instructor = document("instructors", "instructor-montse");
        assertThat(instructor).containsEntry("shortName", "Abonat suprimit #87").containsEntry("memberId", "montse").containsEntry("version", 4L);
        assertThat(document("instructors", "instructor-neus")).containsEntry("shortName", "Neus");
        assertThat(document("class_sessions", "class-montse").get("attendanceSummary", Document.class)).containsEntry("savedByName", "Abonat suprimit #87")
                .containsEntry("savedByAccountId", "montse-account").containsEntry("marked", 3);
        assertThat(document("class_sessions", "class-neus").get("attendanceSummary", Document.class)).containsEntry("savedByName", "Neus");
        assertThat(document("class_sessions", "class-before").get("attendanceSummary", Document.class)).containsEntry("savedByName", "Montse");
        // The catalog's caches hear of it through the outbox, whose event carries the new name only; the processed records of the
        // earlier writes, which carried the old name, are gone (E11-T11), and the other instructor's stay.
        var changed = mongo.find(Query.query(Criteria.where("type").is("InstructorChanged").and("aggregateId").is("instructor-montse")), Document.class, "domain_events");
        assertThat(changed).singleElement().satisfies(event -> {
            assertThat(event).containsEntry("status", "PENDING");
            assertThat(event.toJson()).contains("Abonat suprimit #87").doesNotContain("Montse");
        });
        assertThat(count("domain_events", Criteria.where("type").is("InstructorChanged").and("aggregateId").is("instructor-neus"))).isEqualTo(1);
        dispatcher.dispatch(); dispatcher.dispatch();
        var team = json(admin(get("/api/v1/instructors").param("includeInactive", "true")), 200);
        assertThat(team.path("items").findValuesAsText("shortName")).containsExactlyInAnyOrder("Abonat suprimit #87", "Neus");
    }

    @Test void R_14_15_aRequestBlockedAtItsExecutionStaysBlockedUntilTheDailyRetryPasses() throws Exception {
        String id = json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 201).path("id").asText();
        raw("invoices", "_id", "invoice-late", "clubId", CLUB, "memberId", "montse", "status", "PENDING");
        clock.setInstant(DUE);
        runner.manual(CLUB, JobName.ERASURES, false, "admin-account");
        assertThat(request(id).status()).isEqualTo("BLOCKED");
        assertThat(request(id).blockReasons()).containsExactly("INVOICES_OPEN");
        assertThat(json(admin(get("/api/v1/members/montse/erasure")), 200).path("blockReasons").toString()).isEqualTo("[\"INVOICES_OPEN\"]");
        assertThat(document("members", "montse").getString("firstName")).isEqualTo("Fictional");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("invoice-late")), new org.springframework.data.mongodb.core.query.Update().set("status", "PAID"), "invoices");
        clock.setInstant(DUE.plusSeconds(86400));
        runner.manual(CLUB, JobName.ERASURES, false, "admin-account");
        assertThat(request(id).status()).isEqualTo("ERASING");
        assertThat(document("members", "montse").getString("firstName")).isEqualTo("Abonat suprimit");
    }

    /** WP-14-D's two reads that no rule of S14 §4 carries: `/members/{id}/consents` and `/platform/erasure-requests` (§6). */
    @Test void WP_14_D_consentsAndThePlatformViewOfTheRequests() throws Exception {
        var consents = json(admin(get("/api/v1/members/montse/consents")), 200);
        assertThat(consents).hasSize(2);
        assertThat(consents.get(0).path("type").asText()).isEqualTo("PRIVACY_POLICY");
        assertThat(consents.get(0).path("ipHash").asText()).isEqualTo("hash-montse");
        assertThat(consents.get(1).path("granted").asBoolean()).isFalse();
        assertThat(json(call(get("/api/v1/members/montse/consents"), OTHER, "other-admin-account", "ADMIN"), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        String id = json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 201).path("id").asText();
        var page = json(platform(get("/api/v1/platform/erasure-requests").param("filter", "clubId:eq:" + CLUB)), 200);
        assertThat(page.path("totalItems").asLong()).isEqualTo(1);
        assertThat(page.path("items").get(0).path("id").asText()).isEqualTo(id);
        assertThat(json(platform(get("/api/v1/platform/erasure-requests").param("filter", "clubId:eq:" + OTHER)), 200).path("totalItems").asLong()).isZero();
        assertThat(json(platform(get("/api/v1/platform/erasure-requests").param("filter", "clubId:exists:false")), 200).path("totalItems").asLong()).isZero();
        assertThat(json(admin(get("/api/v1/platform/erasure-requests")), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        // Ids, states and dates only: the list does not search (ruling E75), so q is refused, not ignored.
        assertThat(json(platform(get("/api/v1/platform/erasure-requests").param("q", "montse")), 400).path("code").asText()).isEqualTo("INVALID_FILTER");
    }

    @Test void R_14_14_R_14_15_theConsoleErasesAnAccountAcrossClubsOrAtOnceWithoutMembers() throws Exception {
        member(OTHER, "montse-b", 3, "87654321X", LocalDate.of(2026, 8, 1));
        mongo.remove(Query.query(Criteria.where("accountId").is("montse-b-account")), "memberships");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("montse-b")), new org.springframework.data.mongodb.core.query.Update().set("accountId", "montse-account"), "members");
        membership("montse-account", OTHER, "montse-b", Membership.Status.SUSPENDED);
        var created = json(platform(body(post("/api/v1/accounts/montse-account/erasure"), Map.of("reason", "console"))), 201);
        assertThat(created.path("scope").asText()).isEqualTo("ACCOUNT");
        assertThat(created.path("clubIds").toString()).contains(CLUB, OTHER);
        assertThat(created.path("executeAt").asText()).isEqualTo("2026-09-29T22:00:00Z");
        assertThat(json(platform(body(post("/api/v1/accounts/montse-account/erasure"), Map.of())), 409).path("code").asText()).isEqualTo("ERASURE_ALREADY_REQUESTED");
        assertThat(json(admin(body(post("/api/v1/members/montse/erasure"), Map.of())), 409).path("code").asText()).isEqualTo("ERASURE_ALREADY_REQUESTED");
        // The club sees only its part of it.
        var clubView = json(admin(get("/api/v1/members/montse/erasure")), 200);
        assertThat(clubView.path("clubIds").toString()).isEqualTo("[\"" + CLUB + "\"]");
        assertThat(clubView.path("memberIds").toString()).isEqualTo("[\"montse\"]");
        assertThat(json(admin(delete("/api/v1/members/montse/erasure")), 409).path("code").asText()).isEqualTo("INVALID_STATE");
        String id = created.path("id").asText();
        clock.setInstant(DUE);
        runner.manual(CLUB, JobName.ERASURES, false, "admin-account");
        assertThat(request(id).status()).isEqualTo("SCHEDULED");
        assertThat(request(id).erasedClubIds()).containsExactly(CLUB);
        assertThat(document("accounts", "montse-account").getString("status")).isEqualTo("ACTIVE");
        runner.manual(OTHER, JobName.ERASURES, false, "other-admin-account");
        assertThat(request(id).status()).isIn("ERASING", "ERASED");
        assertThat(document("accounts", "montse-account").getString("status")).isEqualTo("ERASED");
        assertThat(document("members", "montse-b").getString("firstName")).isEqualTo("Abonat suprimit");
        // An account with no member at all is erased at once; an unknown one is 404.
        account("staff-account", "staff@example.test");
        var staff = json(platform(body(post("/api/v1/accounts/staff-account/erasure"), Map.of())), 201);
        assertThat(staff.path("status").asText()).isEqualTo("ERASING");
        assertThat(document("accounts", "staff-account").getString("status")).isEqualTo("ERASED");
        assertThat(json(platform(body(post("/api/v1/accounts/nobody/erasure"), Map.of())), 404).path("code").asText()).isEqualTo("NOT_FOUND");
        assertThat(json(admin(body(post("/api/v1/accounts/staff-account/erasure"), Map.of())), 403).path("code").asText()).isEqualTo("FORBIDDEN");
        assertThat(json(platform(get("/api/v1/platform/erasure-requests").param("filter", "clubId:exists:false")), 200).path("totalItems").asLong()).isEqualTo(2);
    }
}
