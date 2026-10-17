package com.agilityhub.core.platform.privacy;

import com.agilityhub.core.clubs.followup.application.AttachmentStorage;
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
    @Autowired ErasureExecutor executor; @Autowired List<ErasureStep> steps;
    /** The stored objects of T-14-08 (fresh keys each run: the local store never overwrites an object). */
    final String photo = java.util.UUID.randomUUID().toString(), card = java.util.UUID.randomUUID().toString(), taskFile = java.util.UUID.randomUUID().toString();

    @BeforeEach void seed() {
        seedClubs();
        clock.setInstant(REQUESTED);
        member(CLUB, "montse", 87, "12345678Z", LocalDate.of(2026, 8, 31));
        member(CLUB, "active", 12, "X1234567L", null);
    }
    ErasureRequest request(String id) { return mongo.findById(id, ErasureRequest.class); }
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

    /** T-14-08's seed: Montse with two dogs, invoices, bookings, tasks, notices and audit, in every collection R-14-15 lists. */
    void seedEverything() throws Exception {
        storage.put(photo, "image/jpeg", 3, new ByteArrayInputStream(new byte[] {1, 2, 3}));
        storage.put(card, "application/pdf", 3, new ByteArrayInputStream(new byte[] {4, 5, 6}));
        storage.put(taskFile, "image/png", 3, new ByteArrayInputStream(new byte[] {7, 8, 9}));
        dog(CLUB, "kiwi", "montse", "941000000100001", photo);
        dog(CLUB, "blat", "montse", "941000000100002", null);
        dog(CLUB, "other-dog", "active", "941000000100003", null);
        raw("dog_documents", "_id", "doc-kiwi", "clubId", CLUB, "dogId", "kiwi", "type", "VACCINATION", "state", "VALID", "version", 0,
                "files", List.of(map("id", "f1", "fileKey", card, "name", "cartilla-kiwi.pdf", "mimeType", "application/pdf")));
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
        raw("tasks", "_id", "task-1", "clubId", CLUB, "memberId", "montse", "dogId", "kiwi", "text", "practise the weaves", "state", "OPEN",
                "createdBy", map("accountId", "instructor-account", "displayName", "Fictional Instructor"), "doneBy", map("accountId", "montse-account", "displayName", "Fictional Montse"));
        raw("attachments", "_id", "attachment-1", "clubId", CLUB, "entityType", "TASK", "entityId", "task-1", "fileKey", taskFile, "name", "kiwi.png", "version", 0L);
        raw("followup_items", "_id", "item-1", "clubId", CLUB, "memberId", "montse", "dogId", "kiwi", "taskId", "task-1", "textExcerpt", "practise the weaves",
                "authorAccountId", "montse-account", "authorName", "Fictional Montse");
        raw("invoices", "_id", "invoice-1", "clubId", CLUB, "memberId", "montse", "series", "2026", "number", 744L, "displayNumber", "2026-0744", "status", "PAID",
                "total", map("amountMinor", 9000L, "currency", "EUR"), "lines", List.of(map("lineNo", 1, "description", "Quota")),
                "memberSnapshot", map("number", 87, "fullName", "Fictional Montse Person", "taxId", "12345678Z"),
                "paymentMethod", map("type", "SEPA_DD", "maskedAccount", "···· 1332", "holderName", "Fictional Holder", "mandateRef", "rgpd-87-1"));
        raw("collections", "_id", "collection-1", "clubId", CLUB, "invoiceId", "invoice-1", "status", "SUCCEEDED", "providerRef", "provider-ref-1");
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

    @Test void T_14_15_R_14_15_aRequestBlockedAtItsExecutionStaysBlockedUntilTheDailyRetryPasses() throws Exception {
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

    @Test void T_14_14_R_14_18_consentsAndThePlatformViewsOfRequests() throws Exception {
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

    @Test void T_14_14_R_14_15_theConsoleErasesAnAccountAcrossClubsOrAtOnceWithoutMembers() throws Exception {
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
