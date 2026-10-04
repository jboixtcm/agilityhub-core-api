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
    @Autowired com.agilityhub.core.shared.application.SignupCapabilities capabilities;
    @BeforeEach void stripe() {
        for (String collection : List.of("payment_operations", "stripe_events", "checkout_sessions", "upfront_payments")) {
            mongo.remove(Query.query(Criteria.where("clubId").is(CLUB)), collection);
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
    @Test void T_12_31_setupReplacesAnInvalidCardAndDetachedInvalidatesIt() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is("card-member")), new Update().set("paymentMethod.card.invalid", true), "members");
        fake.card("pm_new", new BillingCensusAccess.Card("cus_card-member", "pm_new", "1234", "visa", false));
        var response = ok(as(keyed(post("/api/v1/me/card-setup"), Map.of("successUrl", "https://" + HOST + "/success", "cancelUrl", "https://" + HOST + "/cancel")), CLUB, "MEMBER", "card-member"), 201);
        String session = response.path("checkoutUrl").asText().replace("https://checkout.test/", "");
        webhook("evt_setup", "setup_intent.succeeded", Map.of("id", "seti_example", "customer", "cus_card-member", "payment_method", "pm_new", "metadata", Map.of("memberId", "card-member", "operationId", session)));
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getString("last4")).isEqualTo("1234");
        assertThat(mongo.findById("card-member", Document.class, "members").get("paymentMethod", Document.class).get("card", Document.class).getBoolean("invalid")).isFalse();
        assertThat(events("MemberPaymentMethodChanged")).hasSize(1);
        outbox.dispatch();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("code").is("N-38")), "notifications")).isPositive();
        webhook("evt_detached", "payment_method.detached", Map.of("id", "pm_new"));
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
        webhook("evt_paid_late", "checkout.session.completed", Map.of("id", "cs_example", "payment_intent", "pi_signup", "amount_total", 800,
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
        var object = Map.<String, Object>of("id", "cs_booking", "payment_intent", "pi_booking", "amount_total", 1200, "metadata", Map.of("operationId", session));
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
        recovery.recover();
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
        var object = Map.<String, Object>of("id", "cs_rejected", "payment_intent", "pi_rejected", "amount_total", 1000, "metadata", Map.of("operationId", session));
        webhook("evt_rejected", "checkout.session.completed", object); webhook("evt_rejected_duplicate", "checkout.session.completed", object);
        assertThat(fake.calls().stream().filter(c -> c.operation().equals("refund"))).singleElement().satisfies(call -> assertThat(call.key()).isEqualTo("late:pi_rejected"));
        assertThat(mongo.findById(payment, Document.class, "upfront_payments").getString("status")).isEqualTo("CANCELLED");
        assertThat(events("UpfrontPaymentSucceeded")).isEmpty();
    }

}
