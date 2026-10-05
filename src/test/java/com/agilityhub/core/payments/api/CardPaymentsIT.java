package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.audit.AuditAction;
import com.agilityhub.core.support.AuditCovers;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

class CardPaymentsIT extends BillingItSupport {
    private static final String SECRET = "whsec_example_fixture";
    @Autowired FakePaymentProvider fake;
    @Autowired ProviderSecretVault vault;
    @Autowired PaymentRecovery recovery;
    @Autowired CheckoutService checkouts;
    @Autowired UpfrontPayments upfront;
    @Autowired SignupCheckoutRepository sessions;
    @Autowired BillingTransactions tx;
    @Autowired PaymentPrivacy privacy;
    @Autowired PaymentBookingCancellations cancellations;
    @Autowired CardPayments cards;
    @Autowired PaymentRefunds refunds;
    @Autowired StripeInbox inbox;
    @Autowired PaymentOperationRepository operations;
    @Autowired com.agilityhub.core.shared.application.SignupCapabilities capabilities;
    @BeforeEach void stripe() {
        for (String collection : List.of("payment_operations", "stripe_events", "checkout_sessions", "upfront_payments")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        fake.reset();
        club(CLUB, HOST, "Europe/Madrid", List.of(Module.values()), Map.of("STRIPE", Map.of("enabled", true, "mode", "test",
                "webhookSecretEnc", vault.encrypt(SECRET, CLUB, "STRIPE", "webhookSecretEnc"))));
        mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), "members");
        member("card-member", 301, "Card", "Example", validCard("card-member"), "abonat");
    }
    static Map<String, Object> validCard(String member) {
        return Map.of("type", "CARD", "card", Map.of("stripeCustomerId", "cus_" + member, "stripePaymentMethodId", "pm_" + member, "last4", "4242", "brand", "visa", "invalid", false));
    }
    JsonNode generated() throws Exception { return run("2026-09", simulate("2026-09").path("id").asText()); }
    String runId(JsonNode run) { return run.path("run").path("id").asText(); }
    String invoiceId() { return invoices().getFirst().getString("_id"); }
    JsonNode detail(String id) throws Exception { return ok(admin(get("/api/v1/invoices/" + id)), 200); }
    void charge(String run) throws Exception { ok(admin(keyed(post("/api/v1/billing/runs/" + run + "/card-charges"), Map.of())), 202); }
    FakePaymentProvider.Call firstCharge() { return fake.calls().stream().filter(c -> c.operation().equals("charge")).findFirst().orElseThrow(); }
    String intent(String invoice) { return mongo.findOne(Query.query(Criteria.where("invoiceId").is(invoice).and("providerRef").ne(null)), Document.class, "collections").getString("providerRef"); }
    void webhook(String eventId, String type, Map<String, Object> object) throws Exception {
        String body = mapper.writeValueAsString(Map.of("id", eventId, "type", type, "created", clock.instant().getEpochSecond(), "data", Map.of("object", object)));
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String signature = "t=" + clock.instant().getEpochSecond() + ",v1=" + HexFormat.of().formatHex(mac.doFinal((clock.instant().getEpochSecond() + "." + body).getBytes(StandardCharsets.UTF_8)));
        ok(post("/webhooks/stripe/" + CLUB).header("Stripe-Signature", signature).contentType("application/json").content(body), 200);
    }
    @Test @AuditCovers(AuditAction.CARD_CHARGES_STARTED) void T_12_15_runDuplicateAndOutOfOrderEventsKeepOnePayment() throws Exception {
        String run = runId(generated()), invoice = invoiceId(); charge(run);
        assertThat(fake.calls()).hasSize(1);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("CARD_CHARGES_STARTED")), "audit_entries")).isEqualTo(1);
        assertThat(firstCharge().key()).isEqualTo(invoice);
        assertThat(detail(invoice).path("status").asText()).isEqualTo("COLLECTING");
        var object = Map.<String, Object>of("id", intent(invoice));
        webhook("evt_success", "payment_intent.succeeded", object);
        webhook("evt_success", "payment_intent.succeeded", object);
        webhook("evt_late_failure", "payment_intent.payment_failed", object);
        assertThat(detail(invoice).path("status").asText()).isEqualTo("PAID");
        assertThat(events("InvoicePaid")).hasSize(1);
        assertThat(mongo.findById("evt_late_failure", Document.class, "stripe_events").getString("outcome")).isEqualTo("IGNORED");
        assertThat(ok(admin(get("/api/v1/billing/runs/" + run)), 200).path("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void T_12_15_failureThenSuccessWinsAndRetriesStopAtMaximum() throws Exception {
        admin("bill-admin-notices", CLUB);
        String run = runId(generated()), invoice = invoiceId(); charge(run);
        String original = intent(invoice);
        webhook("evt_failure1", "payment_intent.payment_failed", Map.of("id", original));
        assertThat(detail(invoice).path("status").asText()).isEqualTo("FAILED");
        outbox.dispatch();
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-35")), Document.class, "notifications"))
                .isNotEmpty().allSatisfy(notice -> assertThat(notice.getString("body")).contains("/me/card-setup"));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-10")), "notifications")).isPositive();
        for (int attempt = 2; attempt <= 3; attempt++) {
            var invoiceView = detail(invoice);
            ok(admin(keyed(post("/api/v1/invoices/" + invoice + "/retry"), Map.of("version", invoiceView.path("version").asLong()))), 202);
            assertThat(fake.calls().getLast().key()).isEqualTo(invoice + ":" + attempt);
            String pi = mongo.findOne(Query.query(Criteria.where("invoiceId").is(invoice).and("attempt").is(attempt)), Document.class, "collections").getString("providerRef");
            webhook("evt_failure" + attempt, "payment_intent.payment_failed", Map.of("id", pi));
        }
        error(admin(keyed(post("/api/v1/invoices/" + invoice + "/retry"), Map.of("version", detail(invoice).path("version").asLong()))), 409, "MAX_ATTEMPTS");
        webhook("evt_success_after_failure", "payment_intent.succeeded", Map.of("id", original));
        assertThat(detail(invoice).path("status").asText()).isEqualTo("PAID");
    }
    @Test void T_12_30_missingCardIsSkippedAndRetryRequiresCurrentCard() throws Exception {
        String run = runId(generated()), invoice = invoiceId();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("card-member")), new Update().set("paymentMethod.card.invalid", true), "members");
        var result = ok(admin(keyed(post("/api/v1/billing/runs/" + run + "/card-charges"), Map.of())), 202);
        assertThat(result.path("submitted").asInt()).isZero();
        assertThat(result.path("skipped").get(0).path("reason").asText()).isEqualTo("NO_PAYMENT_METHOD");
        error(admin(keyed(post("/api/v1/invoices/" + invoice + "/retry"), Map.of("version", detail(invoice).path("version").asLong()))), 422, "NO_PAYMENT_METHOD");
    }
    @Test void T_12_31_synchronousExpiredCardInvalidatesOnlyTheChargedMethod() throws Exception {
        fake.fail("expired_card");
        String run = runId(generated()), invoice = invoiceId(); charge(run);
        assertThat(detail(invoice).path("status").asText()).isEqualTo("FAILED");
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getBoolean("invalid")).isTrue();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("card-member")), new Update().set("paymentMethod.card.stripePaymentMethodId", "pm_replacement").set("paymentMethod.card.invalid", false), "members");
        webhook("evt_old_expired_card", "payment_intent.payment_failed", Map.of("id", intent(invoice), "customer", "cus_card-member", "payment_method", "pm_card-member",
                "last_payment_error", Map.of("code", "card_declined", "decline_code", "expired_card")));
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getBoolean("invalid")).isFalse();
    }
    @Test @AuditCovers(AuditAction.PAYMENT_REFUNDED) void T_12_17_partialAndTotalRefundKeepInvoicePaidAndRejectOverRefund() throws Exception {
        String run = runId(generated()), invoice = invoiceId(); charge(run);
        webhook("evt_paid", "payment_intent.succeeded", Map.of("id", intent(invoice)));
        String key = UUID.randomUUID().toString();
        var request = Map.of("amount", Map.of("amountMinor", 2000, "currency", "EUR"), "reason", "Adjustment");
        ok(admin(post("/api/v1/invoices/" + invoice + "/refund").header("Idempotency-Key", key).contentType("application/json").content(mapper.writeValueAsString(request))), 202);
        error(admin(keyed(post("/api/v1/invoices/" + invoice + "/refund"), Map.of("amount", Map.of("amountMinor", 5000, "currency", "EUR"), "reason", "Too much"))), 422, "REFUND_EXCEEDS_PAID");
        String refundOne = mongo.findOne(Query.query(Criteria.where("targetId").is(invoice).and("kind").is("REFUND_INVOICE")), Document.class, "payment_operations").getString("resultId");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("PAYMENT_REFUNDED")), "audit_entries")).isEqualTo(1);
        webhook("evt_refund1", "charge.refunded", Map.of("id", "ch_fixture", "payment_intent", intent(invoice), "currency", "eur", "refunds", Map.of("data", List.of(Map.of("id", refundOne, "amount", 2000, "status", "succeeded")))));
        assertThat(detail(invoice).path("refundedTotal").path("amountMinor").asLong()).isEqualTo(2000);
        ok(admin(keyed(post("/api/v1/invoices/" + invoice + "/refund"), Map.of("reason", "Remainder"))), 202);
        String refundTwo = mongo.findOne(Query.query(Criteria.where("targetId").is(invoice).and("amount.amountMinor").is(4000)), Document.class, "payment_operations").getString("resultId");
        webhook("evt_refund2", "charge.refunded", Map.of("id", "ch_fixture", "payment_intent", intent(invoice), "currency", "eur", "refunds", Map.of("data", List.of(Map.of("id", refundOne, "amount", 2000), Map.of("id", refundTwo, "amount", 4000)))));
        assertThat(detail(invoice).path("refundedTotal").path("amountMinor").asLong()).isEqualTo(6000);
        assertThat(detail(invoice).path("status").asText()).isEqualTo("PAID");
    }
    @Test void T_12_17_refundDeliveredBeforePaymentSuccessIsDeferredAndReplayedOnce() throws Exception {
        String run = runId(generated()), invoice = invoiceId(); charge(run);
        String paymentIntent = intent(invoice);
        var refund = Map.<String, Object>of("id", "ch_early_refund", "payment_intent", paymentIntent, "currency", "eur",
                "refunds", Map.of("data", List.of(Map.of("id", "re_early_refund", "amount", 6000, "status", "succeeded"))));
        webhook("evt_early_refund", "charge.refunded", refund);
        assertThat(mongo.findById("evt_early_refund", Document.class, "stripe_events").getString("outcome")).isEqualTo("FAILED");
        assertThat(detail(invoice).path("status").asText()).isEqualTo("COLLECTING");
        assertThat(detail(invoice).path("refundedTotal").path("amountMinor").asLong()).isZero();

        webhook("evt_success_after_refund", "payment_intent.succeeded", Map.of("id", paymentIntent));
        clock.setInstant(clock.instant().plusSeconds(60)); recovery.recover(); recovery.recover();
        webhook("evt_early_refund", "charge.refunded", refund);
        assertThat(detail(invoice).path("status").asText()).isEqualTo("PAID");
        assertThat(detail(invoice).path("refundedTotal").path("amountMinor").asLong()).isEqualTo(6000);
        var collection = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("invoiceId").is(invoice)), Document.class, "collections");
        assertThat(collection.getString("status")).isEqualTo("REFUNDED");
        assertThat(collection.getList("refunds", Document.class)).hasSize(1);
        assertThat(events("InvoicePaid")).hasSize(1);
        assertThat(mongo.findById("evt_early_refund", Document.class, "stripe_events").getString("outcome")).isEqualTo("PROCESSED");
        assertThat(ok(admin(get("/api/v1/billing/runs/" + run)), 200).path("status").asText()).isEqualTo("COMPLETED");
    }
    @Test void T_12_31_setupReplacesAnInvalidCardAndDetachedInvalidatesIt() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("card-member")), new Update().set("paymentMethod.card.invalid", true), "members");
        fake.card("pm_new", new BillingCensusAccess.Card("cus_card-member", "pm_new", "1234", "visa", false));
        var response = ok(as(keyed(post("/api/v1/me/card-setup"), Map.of("successUrl", "https://" + HOST + "/success", "cancelUrl", "https://" + HOST + "/cancel")), CLUB, "MEMBER", "card-member"), 201);
        String session = response.path("checkoutUrl").asText().replace("https://checkout.test/", "");
        webhook("evt_setup", "setup_intent.succeeded", Map.of("id", "seti_example", "customer", "cus_card-member", "payment_method", "pm_new", "metadata", Map.of("memberId", "card-member", "operationId", session)));
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getString("last4")).isEqualTo("1234");
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getBoolean("invalid")).isFalse();
        assertThat(events("MemberPaymentMethodChanged")).hasSize(1);
        var cardAudits = Query.query(Criteria.where("clubId").is(CLUB).and("action").is("MEMBER_PAYMENT_METHOD_CHANGED"));
        assertThat(mongo.count(cardAudits, "audit_entries")).isEqualTo(1);
        outbox.dispatch();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-38")), "notifications")).isPositive();
        webhook("evt_detached", "payment_method.detached", Map.of("id", "pm_new"));
        assertThat(mongo.count(cardAudits, "audit_entries")).isEqualTo(2);
        assertThat(mongo.find(cardAudits, Document.class, "audit_entries").stream()
                .flatMap(entry -> entry.getList("changes", Document.class).stream()))
                .anySatisfy(change -> {
                    assertThat(change.getString("path")).isEqualTo("paymentMethod.invalid");
                    assertThat(change.get("before")).isEqualTo(false);
                    assertThat(change.get("after")).isEqualTo(true);
                });
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getBoolean("invalid")).isTrue();
        }
        assertThat(simulate("2026-09").path("incidents").toString()).contains("CARD_INVALID");
        outbox.dispatch();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-35")), "notifications")).isPositive();
        mongo.updateFirst(Query.query(Criteria.where("_id").is("card-member")), new Update().set("paymentMethod.card.invalid", false), "members");
        try (var tenant = TenantContext.open(CLUB)) {
            fake.deliverWebhook("customer.deleted", Map.of("eventId", "evt_customer_deleted", "created", clock.instant().getEpochSecond(), "object", Map.of("id", "cus_card-member")));
        }
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getBoolean("invalid")).isTrue();
    }
    @Test void T_12_32_tenantRoleModuleAndManualGuards() throws Exception {
        String run = runId(generated()), invoice = invoiceId();
        error(as(keyed(post("/api/v1/billing/runs/" + run + "/card-charges"), Map.of()), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        for (String role : List.of("MEMBER", "INSTRUCTOR")) {
            error(as(keyed(post("/api/v1/billing/runs/" + run + "/card-charges"), Map.of()), CLUB, role, "card-member"), 403, "FORBIDDEN");
        }
        club(CLUB, HOST, "Europe/Madrid", List.of(Module.values()), providers(false, true, false));
        error(admin(keyed(post("/api/v1/billing/runs/" + run + "/card-charges"), Map.of())), 422, "PAYMENT_PROVIDER_NOT_ENABLED");
        error(admin(keyed(post("/api/v1/invoices/" + invoice + "/refund"), Map.of("reason", "Refund"))), 422, "PAYMENT_PROVIDER_NOT_ENABLED");
        modules(CLUB, List.of());
        error(admin(keyed(post("/api/v1/billing/runs/" + run + "/card-charges"), Map.of())), 404, "MODULE_DISABLED");
    }
    @Test void T_12_15_badSignatureWritesOnlySecurityEvent() throws Exception {
        error(post("/webhooks/stripe/" + CLUB).contentType("application/json").content("{}"), 401, "WEBHOOK_SIGNATURE_INVALID");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB)), "stripe_events")).isZero();
    }
    @Test void T_12_15_twentySixChargesCrossBatchBoundaryAndRequireActionFails() throws Exception {
        for (int i = 0; i < 25; i++) { member("batch-" + i, 400 + i, "Batch", "Example" + i, validCard("batch-" + i), "abonat"); }
        fake.requireAction();
        String run = runId(generated()); charge(run);
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("charge"))).hasSize(26);
        assertThat(events("InvoiceCollecting")).hasSize(26);
        assertThat(events("InvoiceFailed")).hasSize(1);
    }
    @Test @AuditCovers(AuditAction.UPFRONT_PAYMENT_RECORDED) void T_12_16_manualAuditAndExpiredSignupStillRecordsCapturedRemainder() throws Exception {
        try (var tenant = TenantContext.open(CLUB)) {
            tx.run(() -> { upfront.create("card-member", "submission", List.of(new UpfrontPayments.Charge("ENTRY_FEE", null, new Money(1000, "EUR")))); return null; });
            tx.run(() -> { upfront.allocate("card-member", List.of(new UpfrontPayments.Submission(null, "submission")), new Money(200, "EUR")); return null; });
        }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("UPFRONT_PAYMENT_RECORDED")), "audit_entries")).isEqualTo(1);
        String payment = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "upfront_payments").getString("_id");
        var result = ok(admin(keyed(post("/api/v1/checkout-sessions"), Map.of("memberId", "card-member", "upfrontPaymentIds", List.of(payment),
                "successUrl", "https://" + HOST + "/ok", "cancelUrl", "https://" + HOST + "/ko"))), 201);
        String session = result.path("checkoutSessionId").asText();
        webhook("evt_expired", "checkout.session.expired", Map.of("id", "cs_example", "metadata", Map.of("operationId", session)));
        assertThat(mongo.findById(payment, Document.class, "upfront_payments").getString("status")).isEqualTo("PARTIAL");
        fake.card("pi_signup", new BillingCensusAccess.Card("cus_card-member", "pm_recovered", "1234", "visa", false));
        webhook("evt_paid_late", "checkout.session.completed", Map.of("id", "cs_example", "payment_intent", "pi_signup", "amount_total", 800, "payment_status", "paid",
                "metadata", Map.of("operationId", session)));
        assertThat(mongo.findById(payment, Document.class, "upfront_payments").getString("status")).isEqualTo("PAID");
        assertThat(ok(as(get("/api/v1/checkout-sessions/" + session), CLUB, "MEMBER", "card-member"), 200).path("status").asText()).isEqualTo("PAID");
        assertThat(events("UpfrontPaymentSucceeded")).hasSize(1);
        assertThat(events("UpfrontPaymentFailed")).hasSize(1);
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getString("stripePaymentMethodId")).isEqualTo("pm_recovered");
        error(admin(keyed(post("/api/v1/upfront-payments/" + payment + "/refund"), Map.of("amount", Map.of("amountMinor", 1000, "currency", "EUR"), "reason", "Includes manual deposit"))), 422, "REFUND_EXCEEDS_PAID");
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("refund"))).isEmpty();
    }
    @Test void T_12_16_expiredBookingRefundsExactlyOnceAndPrivacyDeletionIsRecoverable() throws Exception {
        String session;
        try (var tenant = TenantContext.open(CLUB)) {
            session = tx.run(() -> checkouts.prepareBooking("card-member", "booking-late", new UpfrontPayments.Charge("SINGLE_CLASS", null, new Money(1200, "EUR")), clock.instant().plusSeconds(900))).sessionId();
            checkouts.expire(session);
        }
        var object = Map.<String, Object>of("id", "cs_booking", "payment_intent", "pi_booking", "amount_total", 1200, "payment_status", "paid", "metadata", Map.of("operationId", session));
        webhook("evt_booking_late", "checkout.session.completed", object);
        webhook("evt_booking_late_again", "checkout.session.completed", object);
        recovery.recover(); recovery.recover();
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("refund"))).hasSize(1);
        assertThat(fake.calls().getFirst().key()).isEqualTo("late:pi_booking");
        try (var tenant = TenantContext.open(CLUB)) { privacy.forgetCustomer("cus_forget"); privacy.forgetCustomer("cus_forget"); }
        recovery.recover(); recovery.recover();
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("forget"))).hasSize(1);
    }
    @Test void T_12_15_storedUnknownPaymentIsDeferredAndReprocessed() throws Exception {
        webhook("evt_early", "payment_intent.succeeded", Map.of("id", "pi_unknown"));
        assertThat(mongo.findById("evt_early", Document.class, "stripe_events").getString("outcome")).isEqualTo("FAILED");
        String run = runId(generated()), invoice = invoiceId(); charge(run);
        mongo.updateFirst(Query.query(Criteria.where("invoiceId").is(invoice)), new Update().set("providerRef", "pi_unknown"), "collections");
        clock.setInstant(clock.instant().plusSeconds(60)); recovery.recover();
        assertThat(detail(invoice).path("status").asText()).isEqualTo("PAID");
        assertThat(mongo.findById("evt_early", Document.class, "stripe_events").getString("outcome")).isEqualTo("PROCESSED");
    }

    @Test void T_12_17_cancellationPoliciesRefundCreditOrLeaveThePaymentAndRefundTargetIsExact() throws Exception {
        for (String policy : List.of("REFUND", "CREDIT", "NONE")) {
            parameter(CLUB, "billing.singleClassCancelPolicy", policy);
            String payment = "payment-" + policy, booking = "booking-" + policy;
            mongo.insert(new UpfrontPayment(payment, CLUB, "card-member", null, "SINGLE_CLASS", null, new Money(1200, "EUR"), new Money(1200, "EUR"),
                    "PAID", "STRIPE", null, clock.instant(), clock.instant(), booking, null, null, null, null,
                    new UpfrontPayment.StripeRefs("pi_" + policy, null), null, null, List.of(), null));
            try (var tenant = TenantContext.open(CLUB)) {
                var event = new PaymentBookingCancellations.Event(CLUB, booking, Map.of("bookingId", booking, "late", false));
                tx.run(() -> { cancellations.handle("cancel-" + policy, event); return null; });
                tx.run(() -> { cancellations.handle("cancel-" + policy, event); return null; });
            }
        }
        recovery.recover();
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("refund"))).hasSize(1);
        assertThat(mongo.count(Query.query(Criteria.where("bookingId").is("booking-CREDIT")), "pending_charges")).isEqualTo(1);
        assertThat(mongo.findOne(Query.query(Criteria.where("bookingId").is("booking-CREDIT")), Document.class, "pending_charges").get("amount", Document.class).getLong("amountMinor")).isEqualTo(-1200);
        String ref = mongo.findOne(Query.query(Criteria.where("targetId").is("payment-REFUND")), Document.class, "payment_operations").getString("resultId");
        webhook("evt_upfront_refund", "charge.refunded", Map.of("id", "ch_refund", "payment_intent", "pi_REFUND", "currency", "eur", "refunds", Map.of("data", List.of(Map.of("id", ref, "amount", 1200, "status", "succeeded")))));
        assertThat(mongo.findById("payment-REFUND", Document.class, "upfront_payments").getString("status")).isEqualTo("REFUNDED");
        assertThat(mongo.findById("payment-NONE", Document.class, "upfront_payments").getString("status")).isEqualTo("PAID");
    }
    @Test void T_12_15_lostResponseReusesRunAndRetryCommandsDespiteChangedVersion() throws Exception {
        String run = runId(generated()), invoice = invoiceId();
        try (var tenant = TenantContext.open(CLUB); var request = com.agilityhub.core.shared.application.IdempotentOperation.referenced("run-request")) {
            cards.chargeRun(run);
        }
        try (var tenant = TenantContext.open(CLUB); var request = com.agilityhub.core.shared.application.IdempotentOperation.referenced("other-run-request")) {
            assertThat(cards.chargeRun(run).submitted()).isZero();
        }
        webhook("evt_declined", "payment_intent.payment_failed", Map.of("id", intent(invoice)));
        try (var tenant = TenantContext.open(CLUB); var request = com.agilityhub.core.shared.application.IdempotentOperation.referenced("run-request")) { assertThat(cards.chargeRun(run).submitted()).isEqualTo(1); }
        long version = detail(invoice).path("version").asLong(); String operation;
        try (var tenant = TenantContext.open(CLUB); var request = com.agilityhub.core.shared.application.IdempotentOperation.referenced("retry-request")) {
            operation = cards.retry(invoice, version); cards.execute(operation);
            assertThat(cards.retry(invoice, version)).isEqualTo(operation); cards.execute(operation);
        }
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("charge"))).hasSize(2);
    }
    @Test void T_12_16_signupCommitFailureReusesOneProviderSetupSession() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("card-member")), new Update()
                .set("contactEmails", List.of(Map.of("email", "signup@example.test", "primary", true))), "members");
        String session;
        try (var tenant = TenantContext.open(CLUB)) {
            String token = capabilities.issue("card-member");
            try (var request = com.agilityhub.core.shared.application.IdempotentOperation.open(() -> {}, (status, body) -> {
                throw new IllegalStateException("Simulated response transaction failure");
            }, "signup-response")) {
                assertThatThrownBy(() -> checkouts.create("card-member", token, "https://" + HOST + "/ok", "https://" + HOST + "/ko", result -> new byte[0]))
                        .isInstanceOf(IllegalStateException.class);
            }
            try (var request = com.agilityhub.core.shared.application.IdempotentOperation.referenced("signup-response")) {
                session = checkouts.create("card-member", token, "https://" + HOST + "/ok", "https://" + HOST + "/ko", result -> new byte[0]).checkoutSessionId();
            }
        }
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("checkout"))).hasSize(1);
        fake.card("pm_signup_setup", new BillingCensusAccess.Card("cus_card-member", "pm_signup_setup", "5555", "mastercard", false));
        webhook("evt_signup_setup", "setup_intent.succeeded", Map.of("id", "seti_signup", "payment_method", "pm_signup_setup", "customer", "cus_card-member",
                "metadata", Map.of("operationId", session, "memberId", "card-member")));
        assertThat(mongo.findById(session, Document.class, "checkout_sessions").getString("status")).isEqualTo("COMPLETE");
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getString("last4")).isEqualTo("5555");
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("MEMBER_PAYMENT_METHOD_CHANGED")), "audit_entries")).isEqualTo(1);
    }
    @Test void T_12_16_rejectedSignupCompletionRefundsOnceWithoutRevivingCancelledRows() throws Exception {
        try (var tenant = TenantContext.open(CLUB)) {
            tx.run(() -> { upfront.create("card-member", "rejected-submission", List.of(new UpfrontPayments.Charge("ENTRY_FEE", null, new Money(1000, "EUR")))); return null; });
        }
        String payment = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "upfront_payments").getString("_id");
        var checkout = ok(admin(keyed(post("/api/v1/checkout-sessions"), Map.of("memberId", "card-member", "upfrontPaymentIds", List.of(payment),
                "successUrl", "https://" + HOST + "/ok", "cancelUrl", "https://" + HOST + "/ko"))), 201);
        String session = checkout.path("checkoutSessionId").asText();
        try (var tenant = TenantContext.open(CLUB)) { checkouts.expire(session); }
        mongo.updateFirst(Query.query(Criteria.where("_id").is(payment)), new Update().set("status", "CANCELLED"), "upfront_payments");
        var object = Map.<String, Object>of("id", "cs_rejected", "payment_intent", "pi_rejected", "amount_total", 1000, "payment_status", "paid", "metadata", Map.of("operationId", session));
        webhook("evt_rejected", "checkout.session.completed", object); webhook("evt_rejected_duplicate", "checkout.session.completed", object);
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("refund"))).singleElement().satisfies(call -> assertThat(call.key()).isEqualTo("late:pi_rejected"));
        assertThat(mongo.findById(payment, Document.class, "upfront_payments").getString("status")).isEqualTo("CANCELLED");
        assertThat(events("UpfrontPaymentSucceeded")).isEmpty();
    }


    String signupCheckout(List<String> rows) throws Exception {
        return ok(admin(keyed(post("/api/v1/checkout-sessions"), Map.of("memberId", "card-member", "upfrontPaymentIds", rows,
                "successUrl", "https://" + HOST + "/ok", "cancelUrl", "https://" + HOST + "/ko"))), 201).path("checkoutSessionId").asText();
    }
    List<String> signupRows(int count) {
        try (var tenant = TenantContext.open(CLUB)) {
            tx.run(() -> { upfront.create("card-member", "round-two", java.util.stream.IntStream.range(0, count)
                    .mapToObj(i -> new UpfrontPayments.Charge(i == 0 ? "ENTRY_FEE" : "FIRST_MONTH", null, new Money(1000, "EUR"))).toList()); return null; });
        }
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB)).with(org.springframework.data.domain.Sort.by("_id")), Document.class, "upfront_payments")
                .stream().map(d -> d.getString("_id")).toList();
    }
    Map<String, Object> completion(String session, String intent, long amount, String status) {
        return Map.of("id", "cs_" + session, "payment_intent", intent, "amount_total", amount, "payment_status", status,
                "metadata", Map.of("operationId", session));
    }
    Document paymentRow(String id) { return mongo.findById(id, Document.class, "upfront_payments"); }
    Map<String, Object> refundObject(String intent, String id, long amount, Map<String, String> metadata) {
        return Map.of("id", "ch_round_two", "payment_intent", intent, "currency", "eur", "refunds", Map.of("data",
                List.of(Map.of("id", id, "amount", amount, "status", "succeeded", "reason", "requested_by_customer", "metadata", metadata))));
    }
    @Test void T_12_17_lateCheckoutCannotOverwriteTheCheckoutThatPaidItsRows() throws Exception {
        var rows = signupRows(1); String a = signupCheckout(rows);
        try (var tenant = TenantContext.open(CLUB)) { checkouts.expire(a); }
        String b = signupCheckout(rows);
        webhook("evt_b_paid", "checkout.session.completed", completion(b, "pi_b", 1000, "paid"));
        Document paid = paymentRow(rows.getFirst());
        webhook("evt_a_late", "checkout.session.completed", completion(a, "pi_a", 1000, "paid"));
        var op = mongo.findOne(Query.query(Criteria.where("key").is("late:pi_a")), Document.class, "payment_operations");
        assertThat(op).isNotNull();
        webhook("evt_a_refund", "charge.refunded", refundObject("pi_a", op.getString("resultId"), 1000,
                Map.of("operationId", op.getString("_id"), "reason", "LATE_COMPLETION")));
        webhook("evt_a_late_again", "checkout.session.completed", completion(a, "pi_a", 1000, "paid"));
        assertThat(paymentRow(rows.getFirst())).isEqualTo(paid);
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("refund"))).singleElement()
                .satisfies(c -> assertThat(((Map<?, ?>) c.request()).get("chargeId")).isEqualTo("pi_a"));
        assertThat(mongo.findById("evt_a_refund", Document.class, "stripe_events").getString("outcome")).isEqualTo("PROCESSED");
        var settled = mongo.findById(op.getString("_id"), Document.class, "payment_operations").get("refund", Document.class);
        assertThat(settled.getString("reason")).isEqualTo("LATE_COMPLETION");
        assertThat(settled.get("byAccountId")).isNull();
    }
    @Test void T_12_17_upfrontRefundBeforeCompletionWaitsAndSettlesOnce() throws Exception {
        var rows = signupRows(1); String session = signupCheckout(rows);
        var refund = refundObject("pi_early_signup", "re_early_signup", 1000, Map.of());
        webhook("evt_signup_refund_first", "charge.refunded", refund);
        var pending = mongo.findById("evt_signup_refund_first", Document.class, "stripe_events");
        assertThat(pending.get("processedAt")).isNull();
        assertThat(pending.getString("outcome")).isEqualTo("FAILED");
        webhook("evt_signup_paid_second", "checkout.session.completed", completion(session, "pi_early_signup", 1000, "paid"));
        assertThat(paymentRow(rows.getFirst()).getString("status")).isEqualTo("PAID");
        clock.setInstant(clock.instant().plusSeconds(60)); recovery.recover(); recovery.recover();
        webhook("evt_signup_refund_first", "charge.refunded", refund);
        assertThat(paymentRow(rows.getFirst()).getString("status")).isEqualTo("REFUNDED");
        assertThat(paymentRow(rows.getFirst()).getList("refunds", Document.class)).hasSize(1);
    }
    @Test void T_12_17_refundMetadataTargetsSecondRowBeforeProviderResultAndKeepsAdminProvenance() throws Exception {
        var rows = signupRows(2); String session = signupCheckout(rows);
        webhook("evt_two_paid", "checkout.session.completed", completion(session, "pi_two", 2000, "paid"));
        fake.beforeRefundReturn((call, result) -> {
            String operation = ((Map<?, ?>) call.request()).get("operationId").toString();
            assertThat(mongo.findById(operation, Document.class, "payment_operations").getString("resultId")).isNull();
            fake.deliverWebhook("charge.refunded", Map.of("eventId", "evt_second_refund", "created", clock.instant().getEpochSecond(), "object",
                    refundObject("pi_two", result.id(), 1000, Map.of("operationId", operation, "reason", "Admin correction", "private", "must-not-be-retained"))));
            assertThat(paymentRow(rows.getFirst()).getString("status")).isEqualTo("PAID");
            assertThat(paymentRow(rows.get(1)).getString("status")).isEqualTo("REFUNDED");
            assertThat(paymentRow(rows.get(1)).getList("refunds", Document.class)).singleElement().satisfies(r -> {
                assertThat(r.getString("reason")).isEqualTo("Admin correction"); assertThat(r.getString("byAccountId")).isEqualTo("bill-admin");
            });
        });
        ok(admin(keyed(post("/api/v1/upfront-payments/" + rows.get(1) + "/refund"), Map.of("reason", "Admin correction"))), 202);
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("refund"))).hasSize(1);
    }

    @Test void T_12_16_unpaidCheckoutNeverSettlesSignupOrBooking() throws Exception {
        var rows = signupRows(1); String signup = signupCheckout(rows), booking;
        try (var tenant = TenantContext.open(CLUB)) {
            booking = tx.run(() -> checkouts.prepareBooking("card-member", "booking-unpaid", new UpfrontPayments.Charge("SINGLE_CLASS", null,
                    new Money(1200, "EUR")), clock.instant().plusSeconds(900))).sessionId();
        }
        for (var entry : Map.of(signup, 1000, booking, 1200).entrySet()) {
            webhook("evt_unpaid_" + entry.getKey(), "checkout.session.completed", completion(entry.getKey(), "pi_unpaid_" + entry.getKey(), entry.getValue(), "unpaid"));
            var event = mongo.findById("evt_unpaid_" + entry.getKey(), Document.class, "stripe_events");
            assertThat(event.getString("outcome")).isEqualTo("IGNORED");
            assertThat(event.getString("reason")).isEqualTo("PAYMENT_NOT_PAID");
            assertThat(mongo.findById(entry.getKey(), Document.class, "checkout_sessions").getString("status")).isEqualTo("PENDING");
        }
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB)), Document.class, "upfront_payments"))
                .allSatisfy(p -> assertThat(p.getString("status")).isEqualTo("CHECKOUT_PENDING"));
        assertThat(events("UpfrontPaymentSucceeded")).isEmpty();
    }
    @Test @org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
    void T_12_15_recoveryIsFairBackedOffAndDeadLettersAfterConfiguredAttempts(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        try (var tenant = TenantContext.open(CLUB)) {
            for (int i = 100; i >= 0; i--) {
                String id = "evt_poison_" + i;
                inbox.receive(new StripeEvent(id, CLUB, id, "charge.refunded", clock.instant().minusSeconds(102 - i), null, null, "fixture"),
                        new Document("created", clock.instant().getEpochSecond()).append("object", refundObject("pi_unknown_" + i, "re_unknown_" + i, 1000, Map.of())));
            }
            inbox.receive(new StripeEvent("evt_recoverable", CLUB, "evt_recoverable", "payment_method.detached", clock.instant(), null, null, "fixture"),
                    new Document("created", clock.instant().getEpochSecond()).append("object", Map.of("id", "pm_card-member")));
        }
        try (var tenant = TenantContext.open(OTHER)) {
            for (int i = 100; i >= 0; i--) { operations.insert(new PaymentOperation("poison_" + i, OTHER, "FORGET", "cus_" + i, "cus_" + i,
                    null, "forget_" + i, null, null, null, clock.instant().minusSeconds(102 - i), null)); }
        }
        try (var tenant = TenantContext.open(CLUB)) { privacy.forgetCustomer("cus_recoverable"); }
        recovery.recover();
        assertThat(mongo.findById("evt_recoverable", Document.class, "stripe_events").get("processedAt")).isNull();
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("forget"))).isEmpty();
        recovery.recover();
        assertThat(mongo.findById("evt_recoverable", Document.class, "stripe_events").getString("outcome")).isEqualTo("PROCESSED");
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("forget"))).hasSize(1);
        var event = mongo.findById("evt_poison_0", Document.class, "stripe_events");
        assertThat(event.getInteger("attempts")).isEqualTo(1);
        assertThat(event.getDate("nextAttemptAt").toInstant()).isAfter(clock.instant());
        for (int i = 0; i < 2; i++) { clock.setInstant(clock.instant().plusSeconds(3600)); recovery.recover(); recovery.recover(); }
        for (String collection : List.of("stripe_events", "payment_operations")) {
            var row = mongo.findById(collection.equals("stripe_events") ? "evt_poison_0" : "poison_0", Document.class, collection);
            assertThat(row.getInteger("attempts")).isEqualTo(3); assertThat(row.getString("outcome")).isEqualTo("FAILED");
            assertThat(row.getDate("processedAt")).isNotNull(); assertThat(row.get("nextAttemptAt")).isNull();
        }
        assertThat(inbox.pending()).noneMatch(e -> e.id().startsWith("evt_poison_"));
        assertThat(operations.pending()).noneMatch(o -> o.id().startsWith("poison_"));
        assertThat(output.getOut()).contains("Stripe event recovery exhausted: eventId=evt_poison_0", "Payment operation recovery exhausted: eventId=poison_0")
                .doesNotContain("cus_recoverable");
        // Profile tests may configure the shared logger for text or JSON; both must carry the real MDC trace.
        String warning = output.getOut().lines().filter(line -> line.contains("Payment operation recovery exhausted: eventId=poison_0 ")).findFirst().orElseThrow();
        assertThat(warning).contains("WARN").containsPattern("(?:traceId=|\"traceId\":\")[0-9a-f-]{36}");
    }
    @Test void T_12_31_cardInvalidIsPublishedOnMemberAndMeOnlyForCards() throws Exception {
        mongo.save(new com.agilityhub.core.identity.persistence.Account("bill-member", "round2@example.test", "Card Example", "ca", null, Set.of(),
                com.agilityhub.core.identity.persistence.Account.Status.ACTIVE, null, Map.of(), false, NOW));
        mongo.save(new com.agilityhub.core.identity.persistence.Membership("round2-membership", "bill-member", CLUB, "card-member",
                Set.of(com.agilityhub.core.identity.domain.Role.MEMBER), com.agilityhub.core.identity.persistence.Membership.Status.ACTIVE, com.agilityhub.core.identity.domain.Role.MEMBER));
        assertCardInvalid(false);
        webhook("evt_view_detached", "payment_method.detached", Map.of("id", "pm_card-member")); assertCardInvalid(true);
        fake.card("pm_view_new", new BillingCensusAccess.Card("cus_card-member", "pm_view_new", "1234", "visa", false));
        var result = ok(as(keyed(post("/api/v1/me/card-setup"), Map.of("successUrl", "https://" + HOST + "/ok", "cancelUrl", "https://" + HOST + "/ko")),
                CLUB, "MEMBER", "card-member"), 201);
        String session = result.path("checkoutUrl").asText().replace("https://checkout.test/", "");
        webhook("evt_view_setup", "setup_intent.succeeded", Map.of("id", "seti_view", "payment_method", "pm_view_new", "metadata", Map.of("memberId", "card-member", "operationId", session)));
        assertCardInvalid(false);
        error(as(get("/api/v1/members/card-member"), OTHER, "ADMIN", null), 404, "NOT_FOUND");
        for (var method : List.of(cash(), sepa(IBAN))) {
            mongo.updateFirst(Query.query(Criteria.where("_id").is("card-member")), new Update().set("paymentMethod", method), "members");
            assertThat(ok(admin(get("/api/v1/members/card-member")), 200).path("paymentMethod").has("invalid")).isFalse();
            assertThat(ok(as(get("/api/v1/me"), CLUB, "MEMBER", "card-member"), 200).path("paymentMethod").has("invalid")).isFalse();
        }
    }
    void assertCardInvalid(boolean invalid) throws Exception {
        for (var response : List.of(ok(admin(get("/api/v1/members/card-member")), 200), ok(as(get("/api/v1/me"), CLUB, "MEMBER", "card-member"), 200))) {
            assertThat(response.path("paymentMethod").path("invalid").isBoolean()).isTrue();
            assertThat(response.path("paymentMethod").path("invalid").asBoolean()).isEqualTo(invalid);
        }
    }
    @Test void T_12_15_chargingRunAcceptsAnotherCommandWithoutResubmittingInvoices() throws Exception {
        String run = runId(generated()); charge(run);
        assertThat(ok(admin(get("/api/v1/billing/runs/" + run)), 200).path("status").asText()).isEqualTo("CHARGING");
        charge(run); assertThat(fake.calls().stream().filter(c -> c.operation().equals("charge"))).hasSize(1);
        assertThat(BillingController.class.getMethod("chargeRunCards", String.class, UUID.class).getAnnotation(io.swagger.v3.oas.annotations.Operation.class).description())
                .contains("GENERATED or CHARGING").doesNotContain("Until E8-T04");
    }
    @Test void T_12_17_pendingRefundKeepsOnlyAllowListedMetadata() throws Exception {
        String run = runId(generated()), invoice = invoiceId(); charge(run);
        webhook("evt_safe_refund", "charge.refunded", refundObject(intent(invoice), "re_safe", 1000,
                Map.of("operationId", "unknown-operation", "reason", "Correction", "email", "private@example.test")));
        var work = mongo.findById("evt_safe_refund", Document.class, "stripe_events").get("work", Document.class);
        var refund = work.get("object", Document.class).get("refunds", Document.class).getList("data", Document.class).getFirst();
        assertThat(refund.get("metadata", Document.class)).containsExactlyInAnyOrderEntriesOf(Map.of("operationId", "unknown-operation", "reason", "Correction"));
        assertThat(work.toJson()).doesNotContain("private@example.test");
    }
    @Test void T_12_15_recoveryUsesTheClubsAttemptLimitAndRetainsItsDeadline() throws Exception {
        parameter(CLUB, "billing.stripeMaxAttempts", 2);
        webhook("evt_limit", "charge.refunded", refundObject("pi_unknown_limit", "re_limit", 1000, Map.of()));
        var first = mongo.findById("evt_limit", Document.class, "stripe_events");
        assertThat(first.getInteger("attempts")).isEqualTo(1);
        recovery.recover(); assertThat(mongo.findById("evt_limit", Document.class, "stripe_events").getInteger("attempts")).isEqualTo(1);
        clock.setInstant(first.getDate("nextAttemptAt").toInstant()); recovery.recover();
        var last = mongo.findById("evt_limit", Document.class, "stripe_events");
        assertThat(last.getInteger("attempts")).isEqualTo(2); assertThat(last.getDate("processedAt")).isNotNull();
        clock.setInstant(clock.instant().plusSeconds(3600)); recovery.recover();
        assertThat(mongo.findById("evt_limit", Document.class, "stripe_events")).isEqualTo(last);
    }
}
