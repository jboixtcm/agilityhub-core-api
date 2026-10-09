package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.*;
import com.agilityhub.core.payments.domain.CapturedPaymentLedger;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.mongodb.core.query.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * E8-T09 round 5 (ruling E97): {@link CapturedPaymentLedger}'s invariants under generated event sequences. Each seed drives one
 * captured 12 € class payment through random admin refunds, REFUND and CREDIT cancellations, late confirmations, command executions
 * (Stripe answers pending or succeeded, loses the response, rejects the call during an outage of the club's access, or refuses its
 * content), refunds made in Stripe's dashboard, refund webhooks (pending, succeeded, failed, canceled; late, duplicated, out of
 * order, or accepted and processed by a later recovery pass), Stripe disabled and re-enabled, the credit billed, recovery passes
 * (restarts) and jumps past Stripe's 24-hour key window. Stripe refuses a refund beyond what is left of the charge. The invariants
 * are checked after every step and again once everything has drained. The findings of the round-4 review and E8-T11's points
 * (the review of round 5, ruling E99) are fixed examples beside the generator.
 */
class CapturedPaymentInvariantsIT extends BillingItSupport {
    private static final String SECRET = "whsec_example_fixture", BOOKING = "inv-booking", PI = "pi_invariants";
    private static final long CAPTURED = 1200;
    private static final int SEEDS = 80, STEPS = 24;
    /** Counterfactual runs against the reviewed code, which stores no CREDIT obligation: check only the money, not the markers. */
    private static final boolean MONEY_ONLY = Boolean.getBoolean("e8t09.moneyOnly");
    @Autowired FakePaymentProvider fake;
    @Autowired ProviderSecretVault vault;
    @Autowired PaymentRecovery recovery;
    @Autowired PaymentRefunds refunds;
    @Autowired CheckoutService checkouts;
    @Autowired UpfrontPayments upfrontPayments;
    @Autowired StripeInbox inbox;
    @Autowired BillingTransactions transactions;
    @Autowired com.agilityhub.core.clubs.messaging.application.FakeEmailSender mail;
    @Autowired com.agilityhub.core.clubs.messaging.application.engine.NotificationDispatcher notifications;

    /** Stripe's side of a refund it created: every status it went through, the last one current. */
    record StripeRefund(String id, long amount, String operationId, List<String> history) {
        String current() { return history.getLast(); }
    }
    private final Map<String, StripeRefund> stripe = new LinkedHashMap<>();
    private final List<String[]> deliveries = new ArrayList<>();
    private final Map<String, Integer> reached = new TreeMap<>();
    private String paymentId = "inv-payment";
    private String lateTarget;
    private boolean enabled;
    private int events;
    private long intentCaptured = CAPTURED;

    @BeforeEach void captured() { reset(); }
    /** The provider bean is shared with the other billing ITs: leave none of this class's hooks behind. */
    @AfterEach void provider() { fake.reset(); }

    void reset() {
        for (String collection : List.of("stripe_refunds", "payment_operations", "stripe_events", "checkout_sessions", "upfront_payments", "pending_charges", "audit_entries", "domain_events", "notifications")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        fake.reset(); stripe.clear(); deliveries.clear(); paymentId = "inv-payment"; lateTarget = null; intentCaptured = CAPTURED;
        // Stripe refuses a refund beyond what is left of the charge (the generator's dashboard refunds can leave less than D).
        fake.beforeRefund(call -> {
            if (((Money) ((Map<?, ?>) call.request()).get("amount")).amountMinor() > available()) {
                throw PaymentNotSubmitted.refused(ErrorCode.PROVIDER_CONFIG_INVALID);
            }
        });
        club(CLUB, HOST, "Europe/Madrid", List.of(Module.values()), Map.of("STRIPE", Map.of("enabled", true, "mode", "test",
                "webhookSecretEnc", vault.encrypt(SECRET, CLUB, "STRIPE", "webhookSecretEnc"))));
        enabled = true;
        mongo.insert(new UpfrontPayment(paymentId, CLUB, "inv-member", null, "SINGLE_CLASS", null, new Money(CAPTURED, "EUR"), new Money(CAPTURED, "EUR"),
                "PAID", "STRIPE", null, clock.instant(), clock.instant(), BOOKING, null, null, null, null,
                new UpfrontPayment.StripeRefs(PI, null), null, null, List.of(), null));
        member("inv-member", 399, "Refund", "Example", card(false), "single");
        recordRefunds(false);
    }

    @Test void T_12_17_round5_generatedSequencesKeepTheLedgerInvariants() {
        reached.clear();
        for (long seed = 1; seed <= SEEDS; seed++) {
            if (seed > 1) { reset(); }
            var random = new Random(seed);
            var trace = new ArrayList<String>();
            try {
                long credited = 0;
                if (seed % 10 == 0) {
                    tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
                    var pending = new StripeRefund("re_generated_pending", 500, null, new ArrayList<>(List.of("pending")));
                    stripe.put(pending.id(), pending);
                    webhook("evt_generated_pending", refundObject(pending, "pending"));
                    invariants(false);
                    bill();
                    String outcome = seed % 20 == 0 ? "succeeded" : "failed";
                    terminate(pending, outcome, "evt_generated_terminal");
                    invariants(false);
                    trace.add("pending dashboard -> bill -> " + outcome);
                    reached.merge("~ pending dashboard billed then " + outcome, 1, Integer::sum);
                }
                for (int step = 0; step < STEPS; step++) {
                    clock.setInstant(clock.instant().plusSeconds(1 + random.nextInt(600)));
                    long processed = mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("processedAt").ne(null)), "stripe_events");
                    String action = step(random, seed, step);
                    trace.add(action);
                    reached.merge(action.replaceAll("[0-9]+", "n"), 1, Integer::sum);
                    if (!enabled && mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("processedAt").ne(null)), "stripe_events") > processed) {
                        reached.merge("~ reconciled while Stripe was disabled", 1, Integer::sum);
                    }
                    invariants(false);
                    long now = ledger().credited();
                    if (credited > 0 && now > credited) { reached.merge("~ credit grown after a failed refund (finding 1)", 1, Integer::sum); }
                    if (now < credited) { reached.merge("~ credit absorbed a dashboard refund (E8-T11 point 3)", 1, Integer::sum); }
                    credited = now;
                }
                trace.add("drain");
                drain(random);
                invariants(true);
                var payment = paymentRow();
                String obligation = payment.getString("refundCompensationReason") != null ? "REFUND" : payment.getString("creditCompensationReason") != null ? "CREDIT" : "none";
                reached.merge("~ obligation " + obligation + (payment.get("compensationInterventionAt") != null ? " with intervention" : ""), 1, Integer::sum);
                if (overpaid() > 0) { reached.merge("~ dashboard refund beyond a billed credit (E8-T11 point 3)", 1, Integer::sum); }
            } catch (Exception | AssertionError failure) {
                throw new AssertionError("seed " + seed + " after " + trace, failure);
            }
        }
        System.out.println("E8-T11, " + SEEDS + " seeds x " + STEPS + " steps reached: " + reached);
        if (!MONEY_ONLY) {
            assertThat(reached).containsKeys("~ credit grown after a failed refund (finding 1)", "~ obligation REFUND with intervention",
                    "~ reconciled while Stripe was disabled", "~ obligation REFUND", "~ obligation CREDIT",
                    "~ compensation postponed by an outage (E8-T11 point 1)", "~ credit absorbed a dashboard refund (E8-T11 point 3)",
                    "~ pending dashboard billed then succeeded", "~ pending dashboard billed then failed");
        }
    }

    @ParameterizedTest(name = "outage {0}")
    @ValueSource(strings = {"PROVIDER_CONFIG_INVALID", "RATE_LIMITED", "PAYMENT_PROVIDER_NOT_ENABLED"})
    void T_12_17_e8t11_point1_outageDefersTheCompensationUntilItPasses(String code) throws Exception {
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        String command = compensationCommands().getFirst().getString("_id");
        // Longer than billing.stripeMaxAttempts (3) retries: an outage spends none of them.
        for (int i = 0; i < 5; i++) {
            fake.rejectRefund(ErrorCode.valueOf(code));
            clock.setInstant(clock.instant().plus(Duration.ofMinutes(6)));
            recovery.recover();
            assertThat(fake.clearRefundRejections()).as("pass %s reached Stripe", i).isZero();
        }
        var waiting = operation(command);
        assertThat(waiting.get("attempts")).isNull();
        assertThat(waiting.get("processedAt")).isNull();
        assertThat(failed(waiting)).isFalse();
        assertThat(waiting.getDate("nextAttemptAt").toInstant()).isAfter(clock.instant());
        assertThat(paymentRow().get("compensationInterventionAt")).isNull();
        assertThat(interventions()).isEmpty();
        assertThat(ledger().reserved()).isEqualTo(CAPTURED);
        // The outage passes: the same command, under its own key, refunds the member.
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(6)));
        recovery.recover();
        String refund = operation(command).getString("resultId");
        assertThat(refund).isNotNull();
        assertThat(fake.calls().stream().filter(call -> call.operation().equals("refund"))).singleElement()
                .satisfies(call -> assertThat(call.key()).isEqualTo("refund:" + paymentId));
        webhook("evt_p1_outage_over", refundObject(stripe.get(refund), "succeeded"));
        assertThat(paymentRow().getString("status")).isEqualTo("REFUNDED");
        invariants(true);
    }

    @ParameterizedTest(name = "compensation {0}")
    @ValueSource(strings = {"failed at Stripe", "refused by Stripe"})
    void T_12_17_e8t11_point1_aNeededInterventionTellsTheAdminOnce(String how) throws Exception {
        fake.refundStatus("pending");
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        String command = compensationCommands().getFirst().getString("_id");
        if (how.startsWith("failed")) {
            tenant(() -> refunds.execute(command));
            terminate(stripe.get(operation(command).getString("resultId")), "failed", "evt_p1_failed");
        } else {
            for (int i = 0; i < 3; i++) {
                fake.refuseRefund(ErrorCode.PROVIDER_CONFIG_INVALID);
                clock.setInstant(clock.instant().plus(Duration.ofMinutes(6)));
                recovery.recover();
            }
        }
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(6)));
        recovery.recover();
        assertThat(compensationCommands()).hasSize(1);
        assertThat(paymentRow().get("compensationInterventionAt")).isNotNull();
        assertThat(interventions()).singleElement().satisfies(entry -> {
            assertThat(entry.getString("action")).isEqualTo("PAYMENT_REFUND_INTERVENTION");
            assertThat(entry.getString("entityType")).isEqualTo("UpfrontPayment");
            assertThat(entry.getString("entityId")).isEqualTo(paymentId);
            assertThat(entry.getString("memberId")).isEqualTo("inv-member");
            assertThat(entry.getString("reason")).isEqualTo("COMPENSATION_INTERVENTION");
            assertThat(amount(entry.get("details", Document.class), "owed")).isEqualTo(CAPTURED);
        });
        // The admin repays exactly what is owed with the existing refund endpoint.
        adminRefund(CAPTURED, "p1-repay");
        assertThat(ledger().free()).isZero();
        invariants(false);
    }

    @Test void T_12_17_e8t11_point2_failedAdminRefundsNeverCapTheCompensation() throws Exception {
        fake.refundStatus("pending");
        var partials = new ArrayList<String>();
        for (int i = 0; i < 4; i++) {
            String key = "p2-partial-" + i;
            partials.add(tenant(() -> refunds.upfront(paymentId, new Money(100, "EUR"), "Partial", key).id()));
            tenant(() -> refunds.execute(partials.getLast()));
        }
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        for (int i = 0; i < partials.size(); i++) {
            terminate(stripe.get(operation(partials.get(i)).getString("resultId")), "failed", "evt_p2_partial_" + i);
        }
        // One supplement per failed admin refund, however many: no compensation failed, so no intervention.
        assertThat(compensationCommands()).extracting(op -> op.getString("key")).containsExactlyInAnyOrder("refund:" + paymentId,
                "refund:" + paymentId + ":1", "refund:" + paymentId + ":2", "refund:" + paymentId + ":3", "refund:" + paymentId + ":4");
        assertThat(paymentRow().get("compensationInterventionAt")).isNull();
        assertThat(interventions()).isEmpty();
        assertThat(ledger().free()).isZero();
        settleAll();
        assertThat(paymentRow().getString("status")).isEqualTo("REFUNDED");
        invariants(true);
    }

    @ParameterizedTest(name = "dashboard refund {0}, credit billed {1}")
    @org.junit.jupiter.params.provider.CsvSource({"500,false", "1200,false", "500,true"})
    void T_12_17_e8t11_point3_dashboardRefundAfterACreditIsReconciledWithIt(long amount, boolean billed) throws Exception {
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
        assertThat(amount(creditRow())).isEqualTo(-CAPTURED);
        if (billed) { bill(); }
        var dashboard = new StripeRefund("re_p3_dashboard", amount, null, new ArrayList<>(List.of("succeeded")));
        stripe.put(dashboard.id(), dashboard);
        webhook("evt_p3_dashboard", refundObject(dashboard, "succeeded"));
        assertThat(refundRows(paymentRow())).extracting(row -> row.getString("providerRef")).containsExactly(dashboard.id());
        if (!billed) {
            assertThat(ledger().credited()).isEqualTo(CAPTURED - amount);
            assertThat(ledger().free()).isZero();
            if (amount == CAPTURED) {
                assertThat(creditRow().get("voidedAt")).isNotNull();
                assertThat(amount(creditRow())).as("a voided credit never reads as a consumption charge").isNegative();
            } else {
                assertThat(amount(creditRow())).isEqualTo(-(CAPTURED - amount));
            }
            assertThat(paymentRow().get("compensationInterventionAt")).isNull();
            assertThat(interventions()).isEmpty();
            // A redelivered cancellation sees the obligation fulfilled.
            tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
            assertThat(ledger().credited()).isEqualTo(CAPTURED - amount);
        } else {
            assertThat(amount(creditRow())).isEqualTo(-CAPTURED);
            assertThat(paymentRow().get("compensationInterventionAt")).isNotNull();
            assertThat(interventions()).singleElement().satisfies(entry -> {
                assertThat(entry.getString("reason")).isEqualTo("REFUND_OVER_CREDIT");
                assertThat(entry.getString("memberId")).isEqualTo("inv-member");
                assertThat(amount(entry.get("details", Document.class), "overpaid")).isEqualTo(amount);
            });
            // A second dashboard refund reports only what it adds.
            var second = new StripeRefund("re_p3_second", 300, null, new ArrayList<>(List.of("succeeded")));
            stripe.put(second.id(), second);
            webhook("evt_p3_second", refundObject(second, "succeeded"));
            assertThat(interventions()).hasSize(2);
            assertThat(overpaid()).isEqualTo(amount + 300);
            assertThat(ledger().refunded() + ledger().credited()).isEqualTo(CAPTURED + overpaid());
        }
        invariants(true);
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void T_12_17_e8t11_point5_interventionWarningIsWrittenOnceAfterCommit(CapturedOutput output) {
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        String command = compensationCommands().getFirst().getString("_id");
        // The state a refund.failed webhook leaves on the compensation, without that webhook's own transaction.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(command)), new Update().set("resultId", "re_p5").set("processedAt", clock.instant())
                .set("refundStatus", "failed"), "payment_operations");
        // Rolled back: nothing was saved, so nothing is reported.
        assertThatThrownBy(() -> tenant(() -> transactions.<Void>run(() -> {
            refunds.compensate(paymentId, "REFUND", false);
            throw new IllegalStateException("rolled back");
        }))).hasMessage("rolled back");
        assertThat(paymentRow().get("compensationInterventionAt")).isNull();
        assertThat(warnings(output)).isEmpty();
        // A write conflict runs the whole transaction again: only the attempt that commits reports, once.
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        tenant(() -> transactions.<Void>run(() -> {
            refunds.compensate(paymentId, "REFUND", false);
            if (attempts.incrementAndGet() == 1) { throw new org.springframework.dao.DuplicateKeyException("write conflict"); }
            return null;
        }));
        assertThat(attempts).hasValue(2);
        assertThat(paymentRow().get("compensationInterventionAt")).isNotNull();
        assertThat(interventions()).hasSize(1);
        assertThat(warnings(output)).singleElement().asString().contains("WARN", "paymentId=" + paymentId);
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        assertThat(warnings(output)).hasSize(1);
    }

    @Test void T_12_17_e8t11_point6_aRejectionStripeNeverReceivedDoesNotReachALaterCall() {
        fake.refundStatus("pending");
        String first = tenant(() -> refunds.upfront(paymentId, new Money(200, "EUR"), "Partial", "p6-first").id());
        stripe(false);
        String label = execute(first, "rejected");
        stripe(true);
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(6)));
        execute(first, "pending");
        assertThat(operation(first).getString("resultId")).as("the later call was rejected by an earlier step's queued rejection").isNotNull();
        assertThat(label).isEqualTo("execute (rejected, not reached)");
    }

    @ParameterizedTest(name = "credit billed before the failure: {0}")
    @ValueSource(booleans = {false, true})
    void T_12_17_round5_finding1_failedPartialRefundCompletesTheCreditOnce(boolean billed) throws Exception {
        fake.refundStatus("pending");
        String partial = tenant(() -> refunds.upfront(paymentId, new Money(200, "EUR"), "Partial", "f1-partial").id());
        tenant(() -> refunds.execute(partial));
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
        assertThat(amount(creditRow())).isEqualTo(-1000);
        if (billed) { bill(); }
        terminate(stripe.get(operation(partial).getString("resultId")), "failed", "evt_f1_failed");
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
        tenant(() -> refunds.lateBooking(paymentId, PI, new Money(CAPTURED, "EUR")));
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("bookingId").is(BOOKING)), "pending_charges")).isEqualTo(1);
        assertThat(compensationCommands()).isEmpty();
        if (!billed) {
            assertThat(amount(creditRow())).isEqualTo(-CAPTURED);
            assertThat(paymentRow().get("compensationInterventionAt")).isNull();
            assertThat(ledger().free()).isZero();
        } else {
            // The invoice already carries the 10 € credit: the 2 € still owed stay recorded for an admin, who can repay exactly them.
            assertThat(amount(creditRow())).isEqualTo(-1000);
            assertThat(paymentRow().get("compensationInterventionAt")).isNotNull();
            assertThat(ledger().free()).isEqualTo(200);
            adminRefund(201, "f1-too-much");
            adminRefund(200, "f1-repay");
            assertThat(ledger().free()).isZero();
        }
        invariants(false);
    }

    @ParameterizedTest(name = "compensation refund {0}")
    @ValueSource(strings = {"failed", "canceled"})
    void T_12_17_round5_finding2_failedCompensationIsTerminalNotReplaced(String status) throws Exception {
        fake.refundStatus("pending");
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        String command = compensationCommands().getFirst().getString("_id");
        tenant(() -> refunds.execute(command));
        var refund = stripe.get(operation(command).getString("resultId"));
        terminate(refund, status, "evt_f2_terminal");
        webhook("evt_f2_redelivered", refundObject(refund, status));
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        tenant(() -> refunds.lateBooking(paymentId, PI, new Money(CAPTURED, "EUR")));
        recovery.recover();
        assertThat(compensationCommands()).hasSize(1);
        assertThat(fake.calls().stream().filter(call -> call.operation().equals("refund"))).hasSize(1);
        assertThat(paymentRow().get("compensationInterventionAt")).isNotNull();
        assertThat(ledger().free()).isEqualTo(CAPTURED);
        invariants(false);
    }

    @Test void T_12_17_round5_finding3_acceptedFailureReconcilesWhileStripeIsDisabled() throws Exception {
        fake.refundStatus("pending");
        String partial = tenant(() -> refunds.upfront(paymentId, new Money(200, "EUR"), "Partial", "f3-partial").id());
        tenant(() -> refunds.execute(partial));
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        String compensation = compensationCommands().getFirst().getString("_id");
        tenant(() -> refunds.execute(compensation));
        var failed = stripe.get(operation(partial).getString("resultId"));
        failed.history().add("failed");
        accept("evt_f3_failed", refundObject(failed, "failed"));
        stripe(false);
        recovery.recover();
        assertThat(mongo.findById("evt_f3_failed", Document.class, "stripe_events").getString("outcome")).isEqualTo("PROCESSED");
        assertThat(failed(operation(partial))).isTrue();
        var supplement = compensationCommands().stream().filter(op -> op.getString("key").equals("refund:" + paymentId + ":1")).findFirst().orElseThrow();
        assertThat(amount(supplement)).isEqualTo(200);
        long calls = fake.calls().size();
        tenant(() -> refunds.execute(supplement.getString("_id")));
        assertThat(fake.calls()).hasSize((int) calls);
        assertThat(operation(supplement.getString("_id")).get("attempts")).isNull();
        assertThat(operation(supplement.getString("_id")).getDate("nextAttemptAt")).isNotNull();
        stripe(true);
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(6)));
        recovery.recover();
        String supplementRefund = operation(supplement.getString("_id")).getString("resultId");
        assertThat(supplementRefund).isNotNull();
        for (String id : List.of(operation(compensation).getString("resultId"), supplementRefund)) {
            stripe.get(id).history().add("succeeded");
            webhook("evt_f3_" + id, refundObject(stripe.get(id), "succeeded"));
        }
        assertThat(paymentRow().getString("status")).isEqualTo("REFUNDED");
        invariants(true);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void T_12_17_e8t11_round2_point1_pendingRefundReducesCreditBeforeBilling(boolean submitted) throws Exception {
        fake.refundStatus("pending");
        String partial = tenant(() -> refunds.upfront(paymentId, new Money(200, "EUR"), "Partial", "r2-p1").id());
        if (submitted) { tenant(() -> refunds.execute(partial)); }
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
        assertThat(amount(creditRow())).isEqualTo(-1000);
        var dashboard = new StripeRefund("re_r2_p1", 100, null, new ArrayList<>(List.of("succeeded")));
        stripe.put(dashboard.id(), dashboard);
        webhook("evt_r2_p1", refundObject(dashboard, "succeeded"));
        assertThat(amount(creditRow())).as("reserve 2 EUR and refund 1 EUR before billing the credit").isEqualTo(-900);
        assertThat(ledger().consistent()).isTrue();
        bill();
        if (!submitted) { tenant(() -> refunds.execute(partial)); }
        terminate(stripe.get(operation(partial).getString("resultId")), "succeeded", "evt_r2_p1_partial");
        assertThat(ledger().refunded() + ledger().credited()).isEqualTo(CAPTURED);
        assertThat(interventions()).isEmpty();
        invariants(true);
    }

    @com.agilityhub.core.support.AuditCovers(com.agilityhub.core.platform.application.audit.AuditAction.PAYMENT_REFUND_INTERVENTION)
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"ca,false", "es,false", "en,false", "ca,true", "es,true", "en,true"})
    void T_12_17_e8t11_round2_point2_adminReceivesAppAndEmailOnce(String locale, boolean billed) throws Exception {
        notificationRecipients(locale);
        if (billed) {
            tenant(() -> refunds.compensate(paymentId, "CREDIT", false)); bill();
            var dashboard = new StripeRefund("re_r2_p2_dashboard", 500, null, new ArrayList<>(List.of("succeeded")));
            stripe.put(dashboard.id(), dashboard);
            webhook("evt_r2_p2", refundObject(dashboard, "succeeded"));
            webhook("evt_r2_p2_copy", refundObject(dashboard, "succeeded"));
        } else {
            fake.refundStatus("pending");
            tenant(() -> refunds.compensate(paymentId, "REFUND", false));
            String command = compensationCommands().getFirst().getString("_id");
            tenant(() -> refunds.execute(command));
            var failed = stripe.get(operation(command).getString("resultId"));
            terminate(failed, "failed", "evt_r2_p2");
            webhook("evt_r2_p2_copy", refundObject(failed, "failed"));
        }
        tenant(() -> refunds.compensate(paymentId, billed ? "CREDIT" : "REFUND", false));
        deliverInterventions(1, locale, billed ? "5" : "12");
        assertThat(interventions()).singleElement().satisfies(entry ->
                assertThat(entry.getString("action")).isEqualTo("PAYMENT_REFUND_INTERVENTION"));
        assertThat(events("UpfrontRefundIntervention")).singleElement().satisfies(event -> {
            var payload = event.get("payload", Document.class);
            assertThat(payload.getString("paymentId")).isEqualTo(paymentId);
            assertThat(payload.getString("memberId")).isEqualTo("inv-member");
            assertThat(payload.getString("reason")).isEqualTo(billed ? "REFUND_OVER_CREDIT" : "COMPENSATION_INTERVENTION");
            assertThat(amount(payload, "amount")).isEqualTo(billed ? 500 : CAPTURED);
        });
    }

    @Test void T_12_17_e8t11_round2_point3_laterFailureUpdatesTheWholeOwedBalance() throws Exception {
        notificationRecipients("ca");
        fake.refundStatus("pending");
        String partial = tenant(() -> refunds.upfront(paymentId, new Money(200, "EUR"), "Partial", "r2-p3").id());
        tenant(() -> refunds.execute(partial));
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        String command = compensationCommands().getFirst().getString("_id");
        tenant(() -> refunds.execute(command));
        terminate(stripe.get(operation(command).getString("resultId")), "failed", "evt_r2_p3_compensation");
        assertThat(interventions()).extracting(e -> amount(e.get("details", Document.class), "owed")).containsExactly(1000L);
        var failed = stripe.get(operation(partial).getString("resultId"));
        terminate(failed, "failed", "evt_r2_p3_partial");
        webhook("evt_r2_p3_duplicate", refundObject(failed, "failed"));
        tenant(() -> refunds.compensate(paymentId, "REFUND", false));
        assertThat(interventions()).extracting(e -> amount(e.get("details", Document.class), "owed")).containsExactly(1000L, 1200L);
        assertThat(amount(paymentRow(), "compensationInterventionOwed")).isEqualTo(1200);
        deliverInterventions(2, "ca", "10", "12");
        assertThat(compensationCommands()).hasSize(1);
        // The admin's new reservation and its success reduce the persisted balance to zero, with no new demand.
        String repayment = tenant(() -> refunds.upfront(paymentId, new Money(CAPTURED, "EUR"), "Repayment", "r2-p3-repay").id());
        assertThat(amount(paymentRow(), "compensationInterventionOwed")).isZero();
        tenant(() -> refunds.execute(repayment));
        terminate(stripe.get(operation(repayment).getString("resultId")), "succeeded", "evt_r2_p3_repaid");
        assertThat(interventions()).hasSize(2);
        invariants(true);
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void T_12_17_e8t11_round2_point4_billedCreditWarnsOnceOnlyAfterCommit(CapturedOutput output) throws Exception {
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false)); bill();
        assertThatThrownBy(() -> tenant(() -> transactions.<Void>run(() -> {
            refunds.reconcile(PI, "re_r2_p4_rollback", new Money(500, "EUR"), "succeeded", clock.instant(), "requested_by_customer", null);
            throw new IllegalStateException("rolled back");
        }))).hasMessage("rolled back");
        assertThat(warnings(output)).isEmpty();
        assertThat(events("UpfrontRefundIntervention")).isEmpty();
        for (int i = 1; i <= 2; i++) {
            var dashboard = new StripeRefund("re_r2_p4_" + i, i == 1 ? 500 : 300, null, new ArrayList<>(List.of("succeeded")));
            stripe.put(dashboard.id(), dashboard);
            webhook("evt_r2_p4_" + i, refundObject(dashboard, "succeeded"));
            webhook("evt_r2_p4_duplicate_" + i, refundObject(dashboard, "succeeded"));
        }
        assertThat(interventions()).hasSize(2);
        assertThat(overpaid()).isEqualTo(800);
        assertThat(warnings(output)).singleElement().asString().contains("WARN", "paymentId=" + paymentId);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"succeeded,true", "failed,true", "canceled,true", "failed,false", "canceled,false"})
    void T_12_17_e8t12_point1_pendingDashboardRefundIsReservedBeforeBilling(String outcome, boolean billed) throws Exception {
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
        var dashboard = new StripeRefund("re_e8t12_pending", 500, null, new ArrayList<>(List.of("pending")));
        stripe.put(dashboard.id(), dashboard);
        webhook("evt_e8t12_pending", refundObject(dashboard, "pending"));
        webhook("evt_e8t12_pending_copy", refundObject(dashboard, "pending"));
        assertThat(ledger().reserved()).isEqualTo(500);
        assertThat(amount(creditRow())).as("reserve the dashboard refund before billing consumes the credit").isEqualTo(-700);
        assertThat(ledger().consistent()).isTrue();
        if (billed) { bill(); }
        terminate(dashboard, outcome, "evt_e8t12_terminal");
        webhook("evt_e8t12_terminal_copy", refundObject(dashboard, outcome));
        assertThat(ledger().reserved()).isZero();
        assertThat(ledger().refunded()).isEqualTo("succeeded".equals(outcome) ? 500 : 0);
        if (terminalFailure(outcome) && billed) {
            assertThat(amount(creditRow())).isEqualTo(-700);
            assertThat(amount(paymentRow(), "compensationInterventionOwed")).isEqualTo(500);
            assertThat(interventions()).hasSize(1);
        } else {
            assertThat(ledger().refunded() + ledger().credited()).isEqualTo(CAPTURED);
            assertThat(interventions()).isEmpty();
        }
        assertThat(overpaid()).isZero();
        invariants(true);
    }

    @Test void T_12_17_e8t12_point1_pendingDashboardReducesNewAdminReservations() throws Exception {
        var dashboard = new StripeRefund("re_e8t12_admin", 500, null, new ArrayList<>(List.of("pending")));
        stripe.put(dashboard.id(), dashboard);
        webhook("evt_e8t12_admin", refundObject(dashboard, "pending"));
        assertThatThrownBy(() -> tenant(() -> refunds.upfront(paymentId, new Money(701, "EUR"), "Partial", "e8t12-too-much")))
                .isInstanceOf(ApiException.class).hasMessage("REFUND_EXCEEDS_PAID");
        // Same provider identifier in another club must not reduce this club's remaining 7 EUR.
        mongo.insert(new Document("_id", OTHER + ":re_other").append("clubId", OTHER).append("intent", PI).append("status", "pending")
                .append("amount", new Document("amountMinor", 1200L).append("currency", "EUR")), "stripe_refunds");
        tenant(() -> refunds.upfront(paymentId, new Money(700, "EUR"), "Partial", "e8t12-remaining"));
        assertThat(ledger().reserved()).isEqualTo(CAPTURED);
        assertThat(ledger().consistent()).isTrue();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void T_12_17_e8t12_point1_linkedPendingRefundReservesOnlyOnce(boolean lostResponse) throws Exception {
        fake.refundStatus("pending");
        String id = tenant(() -> refunds.upfront(paymentId, new Money(500, "EUR"), "Partial", "e8t12-linked").id());
        execute(id, lostResponse ? "lost response" : "pending");
        var linked = stripe.values().iterator().next();
        if (lostResponse) { assertThat(operation(id).getString("resultId")).isNull(); }
        webhook("evt_e8t12_linked", refundObject(linked, "pending"));
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
        assertThat(ledger().reserved()).isEqualTo(500);
        assertThat(amount(creditRow())).isEqualTo(-700);
        bill(); terminate(linked, "succeeded", "evt_e8t12_linked_success");
        invariants(true);
    }

    @ExtendWith(OutputCaptureExtension.class)
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"failed,true", "canceled,true", "refused,true", "failed,false", "refused,false"})
    void T_12_17_e8t12_point2_expiredCheckoutFailureNotifiesTheAdmin(String outcome, boolean booking, CapturedOutput output) throws Exception {
        notificationRecipients("ca");
        if ("refused".equals(outcome)) {
            for (int i = 0; i < 3; i++) { fake.refuseRefund(ErrorCode.PROVIDER_CONFIG_INVALID); }
        }
        String command = expiredCheckout(booking);
        if ("refused".equals(outcome)) {
            for (int i = 0; i < 3; i++) { clock.setInstant(clock.instant().plusSeconds(360)); recovery.recover(); }
        } else {
            var refund = stripe.get(operation(command).getString("resultId"));
            terminate(refund, outcome, "evt_e8t12_late_failure");
            webhook("evt_e8t12_late_copy", refundObject(refund, outcome));
        }
        recovery.recover(); recovery.recover();
        assertThat(failed(operation(command))).isTrue();
        assertThat(interventions()).singleElement().satisfies(entry -> {
            assertThat(entry.getString("entityId")).isEqualTo(paymentId);
            assertThat(entry.getString("reason")).isEqualTo("COMPENSATION_INTERVENTION");
            assertThat(amount(entry.get("details", Document.class), "owed")).isEqualTo(CAPTURED);
        });
        assertThat(events("UpfrontRefundIntervention")).singleElement().satisfies(event -> {
            assertThat(event.get("payload", Document.class).getString("paymentId")).isEqualTo(paymentId);
            assertThat(amount(event.get("payload", Document.class), "amount")).isEqualTo(CAPTURED);
        });
        deliverInterventions(1, "ca", "12");
        assertThat(paymentRow().getString("status")).isEqualTo("CANCELLED");
        assertThat(amount(paymentRow(), "amountPaid")).isZero();
        assertThat(paymentRow().get("stripe")).isNull();
        assertThat(operations()).hasSize(1);
        assertThat(paymentRow().get("compensationInterventionAt")).as("late capture does not stop a different capture's compensation").isNull();
        // The production log scrubber may redact part of a generated UUID; identity is asserted in audit and outbox above.
        assertThat(output.getOut().lines().filter(line -> line.contains("Refund compensation needs an admin:")).toList()).hasSize(1);
    }

    @Test void T_12_17_e8t12_point2_generatedExpiredCheckoutSequences() throws Exception {
        var outcomes = new TreeSet<String>();
        for (int seed = 1; seed <= 24; seed++) {
            if (seed > 1) { reset(); }
            var random = new Random(seed);
            var trace = new ArrayList<String>();
            try {
                String command = expiredCheckout();
                for (int step = 0; step < STEPS; step++) {
                    clock.setInstant(clock.instant().plusSeconds(1 + random.nextInt(600)));
                    int choice = random.nextInt(5);
                    if (choice < 3) {
                        var refund = stripe.values().iterator().next();
                        if (seed % 2 == 0 && "succeeded".equals(refund.current())) {
                            send("evt_inv_" + (++events), refund.id(), "succeeded", random);
                            trace.add("duplicate success");
                        } else { trace.add(webhook(random)); }
                    }
                    else if (choice == 3) { stripe(!enabled); trace.add("Stripe " + enabled); }
                    else { recovery.recover(); trace.add("recover"); }
                    lateInvariants(command, false);
                }
                drain(random);
                lateInvariants(command, true);
                outcomes.add(failed(operation(command)) ? "failed with intervention" : "succeeded");
            } catch (Exception | AssertionError failure) {
                throw new AssertionError("expired seed " + seed + " after " + trace, failure);
            }
        }
        assertThat(outcomes).containsExactlyInAnyOrder("failed with intervention", "succeeded");
        System.out.println("E8-T12 expired checkout generator: 24 seeds x " + STEPS + " steps; " + outcomes);
    }
    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"succeeded,true", "succeeded,false", "failed,true", "failed,false"})
    void T_12_17_e8t12_round2_point1_sharedCaptureHasOneAllowance(String outcome, boolean dashboardFirst) throws Exception {
        sharedCapture();
        String local = tenant(() -> refunds.upfront(paymentId, new Money(1200, "EUR"), "Partial", "shared-local").id());
        execute(local, "pending");
        var dashboard = dashboardRefund(1200, "pending");
        assertThatThrownBy(() -> tenant(() -> refunds.upfront("inv-payment-b", new Money(1, "EUR"), "Third refund", "shared-third")))
                .isInstanceOf(ApiException.class).hasMessage("REFUND_EXCEEDS_PAID");
        tenant(() -> refunds.compensate("inv-payment-b", "CREDIT", false));
        assertThat(mongo.findOne(Query.query(Criteria.where("bookingId").is("inv-booking-b")), Document.class, "pending_charges")).isNull();
        var own = stripe.get(operation(local).getString("resultId"));
        var first = dashboardFirst ? dashboard : own;
        var second = dashboardFirst ? own : dashboard;
        terminate(first, first == own ? outcome : "succeeded", "evt_shared_first");
        sharedInvariants();
        terminate(second, second == own ? outcome : "succeeded", "evt_shared_second");
        sharedInvariants();
        assertThat(mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("intent").is(PI)), Document.class, "stripe_refunds"))
                .hasSize(2);
        assertThat(allRefunded()).isEqualTo("succeeded".equals(outcome) ? 2400 : 1200);
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("processedAt").is(null)), "stripe_events")).isZero();
    }

    @Test void T_12_17_e8t12_round2_point1_sharedSequencesConserveTheIntent() throws Exception {
        var reach = new TreeSet<String>();
        for (int seed = 1; seed <= 32; seed++) {
            reset(); sharedCapture();
            var random = new Random(seed);
            var trace = new ArrayList<String>();
            try {
                for (int step = 0; step < STEPS; step++) {
                    clock.setInstant(clock.instant().plusSeconds(360));
                    int choice = random.nextInt(7);
                    if (choice == 0 || stripe.isEmpty() && pendingCommands().isEmpty()) {
                        String target = random.nextBoolean() ? paymentId : "inv-payment-b";
                        long requested = 1 + random.nextInt(1200);
                        long allowance = intentCaptured - allRefunded() - allReserved() - allCredited();
                        String key = "shared-" + seed + "-" + step;
                        try {
                            tenant(() -> refunds.upfront(target, new Money(requested, "EUR"), "Generated", key));
                            assertThat(requested).as("intent allowance").isLessThanOrEqualTo(allowance);
                            trace.add("accepted " + target + " " + requested);
                        } catch (ApiException rejected) {
                            assertThat(rejected.code()).isIn(ErrorCode.REFUND_EXCEEDS_PAID, ErrorCode.INVALID_STATE);
                            trace.add("refused " + target);
                        }
                    } else if (choice == 1 || stripe.isEmpty()) {
                        trace.add(execute(random));
                    } else if (choice == 2) {
                        // The dashboard can overtake a command not yet sent. Do not create synthetic over-reservations here:
                        // this generator checks strict conservation; the existing generator covers that provider race.
                        long free = Math.min(available(), intentCaptured - allRefunded() - allReserved());
                        if (free > 0) { dashboardRefund(1 + random.nextInt((int) free), random.nextBoolean() ? "pending" : "succeeded"); }
                        trace.add("dashboard");
                    } else if (choice == 3) { trace.add(webhook(random)); }
                    else if (choice == 4) { recovery.recover(); trace.add("recover"); }
                    else {
                        String target = random.nextBoolean() ? paymentId : "inv-payment-b";
                        String policy = choice == 5 ? "CREDIT" : "REFUND";
                        tenant(() -> refunds.compensate(target, policy, false));
                        trace.add("cancel " + policy);
                    }
                    reach.add(trace.getLast().split(" ")[0]);
                    sharedInvariants();
                }
                drain(random); sharedInvariants();
                assertThat(allReserved()).isZero();
            } catch (Exception | AssertionError failure) { throw new AssertionError("shared seed " + seed + " after " + trace, failure); }
        }
        assertThat(reach).contains("accepted", "refused", "dashboard", "webhook", "execute", "recover", "cancel");
        System.out.println("E8-T12 round 2 shared generator: 32 seeds x " + STEPS + " steps; " + reach);
    }

    @Test void T_12_17_e8t12_round2_point1_concurrentRowsRecheckTheCaptureAfterCommit() throws Exception {
        sharedCapture();
        var held = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> tenant(() -> transactions.run(() -> {
                refunds.upfront(paymentId, new Money(1200, "EUR"), "First", "concurrent-first");
                refunds.reconcile(PI, "re_concurrent_external", new Money(1200, "EUR"), "pending", clock.instant(), "requested_by_customer", null);
                held.countDown();
                try { assertThat(release.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException interrupted) { throw new IllegalStateException(interrupted); }
                return null;
            })));
            try {
                assertThat(held.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(() -> {
                    entered.countDown();
                    return tenant(() -> {
                        try { refunds.upfront("inv-payment-b", new Money(1, "EUR"), "Third", "concurrent-third"); return "ACCEPTED"; }
                        catch (ApiException rejected) { return rejected.getMessage(); }
                    });
                });
                assertThat(entered.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> second.get(150, java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                release.countDown();
                first.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(second.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("REFUND_EXCEEDS_PAID");
            } finally { release.countDown(); }
        }
        assertThat(allReserved()).isEqualTo(2400);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"500,true", "1500,true", "2400,true", "500,false", "1500,false", "2400,false"})
    void T_12_17_e8t12_round2_point1_dashboardUsesUncreditedCaptureBeforeReportingOverpayment(long repaid, boolean billed) throws Exception {
        sharedCapture();
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false));
        if (billed) { bill(); }
        var external = dashboardRefund(repaid, "pending");
        assertThat(overpaid()).isZero();
        assertThat(allCredited()).isEqualTo(billed ? 1200 : Math.min(1200, 2400 - repaid));
        terminate(external, "succeeded", "evt_shared_credit_success");
        assertThat(overpaid()).as("actual capture-wide overpayment").isEqualTo(billed ? Math.max(0, repaid - 1200) : 0);
        assertThat(allRefunded() + allCredited()).isLessThanOrEqualTo(2400 + overpaid());
    }

    @Test void T_12_17_e8t12_round2_point1_sharedCreditsAbsorbBeforeABilledCreditNeedsIntervention() throws Exception {
        sharedCapture();
        tenant(() -> refunds.compensate(paymentId, "CREDIT", false)); bill();
        tenant(() -> refunds.compensate("inv-payment-b", "CREDIT", false));
        var external = dashboardRefund(500, "pending");
        assertThat(allCredited()).isEqualTo(1900);
        terminate(external, "succeeded", "evt_shared_two_credits");
        assertThat(overpaid()).isZero();
        assertThat(allRefunded() + allCredited()).isEqualTo(2400);
    }

    @ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"500,succeeded,true", "1200,succeeded,true", "500,pending,true", "1200,pending,true", "500,succeeded,false", "1200,succeeded,false"})
    void T_12_17_e8t12_round2_point2_lateInterventionSubtractsDashboardRepayments(long repaid, String status, boolean booking) throws Exception {
        notificationRecipients("ca");
        fake.rejectRefund(ErrorCode.PROVIDER_CONFIG_INVALID);
        String command = expiredCheckout(booking);
        var external = dashboardRefund(repaid, status);
        assertProcessedRefund(external.id());
        exhaustLate(command);
        long owed = CAPTURED - repaid;
        assertThat(amount(operation(command), "interventionOwed")).isEqualTo(owed);
        assertThat(interventions()).hasSize(owed > 0 ? 1 : 0);
        if (owed > 0) {
            assertThat(amount(events("UpfrontRefundIntervention").getFirst().get("payload", Document.class), "amount")).isEqualTo(owed);
            deliverInterventions(1, "ca", "7");
            var remainder = dashboardRefund(owed, "pending");
            assertThat(amount(operation(command), "interventionOwed")).as("in-flight repayment closes the demand").isZero();
            terminate(remainder, "succeeded", "evt_late_remainder");
        }
        if ("pending".equals(status)) { terminate(external, "succeeded", "evt_late_external_success"); }
        webhook("evt_late_external_duplicate", refundObject(external, "succeeded"));
        recovery.recover();
        assertThat(amount(operation(command), "interventionOwed")).isZero();
        assertThat(interventions()).hasSize(owed > 0 ? 1 : 0);
        assertThat(paymentRow().get("stripe")).isNull();
        assertThat(amount(paymentRow(), "amountPaid")).isZero();
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("processedAt").is(null)), "stripe_events")).isZero();
    }

    @Test void T_12_17_e8t12_round2_point2_generatedLateRepaymentsKeepTheBalanceCurrent() throws Exception {
        var reach = new TreeSet<String>();
        for (int seed = 1; seed <= 24; seed++) {
            reset(); fake.rejectRefund(ErrorCode.PROVIDER_CONFIG_INVALID);
            String command = expiredCheckout(seed % 2 == 0);
            var random = new Random(seed);
            var trace = new ArrayList<String>();
            try {
                dashboardRefund(seed % 3 == 0 ? CAPTURED : 1 + random.nextInt(1199), seed % 2 == 0 ? "pending" : "succeeded");
                exhaustLate(command);
                for (int step = 0; step < STEPS; step++) {
                    if (random.nextBoolean() && available() > 0) { trace.add(dashboard(random)); }
                    else { trace.add(webhook(random)); }
                    recovery.recover();
                    long settled = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("intent").is(PI).and("status").in("pending", "succeeded")), Document.class, "stripe_refunds")
                            .stream().mapToLong(this::amount).sum();
                    long expected = Math.max(0, CAPTURED - settled);
                    assertThat(amount(operation(command), "interventionOwed")).isEqualTo(expected);
                    reach.add(expected == 0 ? "closed" : "owed");
                    assertThat(events("UpfrontRefundIntervention")).hasSize(interventions().size());
                    assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("outcome").is("FAILED")), "stripe_events")).isZero();
                }
                drain(random);
            } catch (Exception | AssertionError failure) { throw new AssertionError("late repayment seed " + seed + " after " + trace, failure); }
        }
        assertThat(reach).containsExactlyInAnyOrder("closed", "owed");
        System.out.println("E8-T12 round 2 late repayment generator: 24 seeds x " + STEPS + " steps; " + reach);
    }

    @Test void T_12_17_e8t12_round2_point2_lateRepaymentDoesNotSpendAnotherCapture() throws Exception {
        fake.rejectRefund(ErrorCode.PROVIDER_CONFIG_INVALID);
        String command = expiredCheckout();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(paymentId)), new Update().set("status", "PAID")
                .set("stripe", new Document("paymentIntentId", "pi_new_capture").append("chargeId", null))
                .set("amountPaid", new Document("amountMinor", 1200L).append("currency", "EUR")), "upfront_payments");
        var external = dashboardRefund(1200, "succeeded");
        assertProcessedRefund(external.id()); exhaustLate(command);
        assertThat(amount(operation(command), "interventionOwed")).isZero();
        assertThat(paymentRow().get("stripe", Document.class).getString("paymentIntentId")).isEqualTo("pi_new_capture");
        assertThat(paymentRow().getString("status")).isEqualTo("PAID");
        assertThat(refundRows(paymentRow())).isEmpty();
        assertThat(interventions()).isEmpty();
    }

    void sharedCapture() throws Exception {
        intentCaptured = 2400;
        mongo.insert(new UpfrontPayment("inv-payment-b", CLUB, "inv-member", null, "SINGLE_CLASS", null, new Money(CAPTURED, "EUR"), new Money(CAPTURED, "EUR"),
                "PAID", "STRIPE", null, clock.instant(), clock.instant(), "inv-booking-b", null, null, null, null,
                new UpfrontPayment.StripeRefs(PI, null), null, null, List.of(), null));
        mongo.updateMulti(Query.query(Criteria.where("clubId").is(CLUB)), new Update().set("status", "DUE")
                .set("amountPaid", new Document("amountMinor", 0L).append("currency", "EUR")).unset("stripe").unset("paidAt"), "upfront_payments");
        String session = ok(admin(keyed(post("/api/v1/checkout-sessions"), Map.of("memberId", "inv-member", "upfrontPaymentIds", List.of(paymentId, "inv-payment-b"),
                "successUrl", "https://" + HOST + "/ok", "cancelUrl", "https://" + HOST + "/ko"))), 201).path("checkoutSessionId").asText();
        webhook("evt_shared_capture", "checkout.session.completed", Map.of("id", "cs_shared", "payment_intent", PI,
                "amount_total", intentCaptured, "payment_status", "paid", "metadata", Map.of("operationId", session)));
        assertThat(intentRows()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getString("status")).isEqualTo("PAID");
            assertThat(amount(row, "amountPaid")).isEqualTo(CAPTURED);
        });
    }
    StripeRefund dashboardRefund(long amount, String status) throws Exception {
        var external = new StripeRefund("re_round2_dashboard_" + (++events), amount, null, new ArrayList<>(List.of(status)));
        stripe.put(external.id(), external);
        webhook("evt_round2_dashboard_" + events, refundObject(external, status));
        return external;
    }
    void exhaustLate(String command) {
        for (int i = 0; i < 3; i++) {
            fake.refuseRefund(ErrorCode.PROVIDER_CONFIG_INVALID);
            clock.setInstant(clock.instant().plusSeconds(360)); recovery.recover();
        }
        assertThat(failed(operation(command))).isTrue();
    }
    void assertProcessedRefund(String id) {
        assertThat(mongo.findById(CLUB + ":" + id, Document.class, "stripe_refunds")).as("external refund reconciled").isNotNull();
    }
    List<Document> intentRows() { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("stripe.paymentIntentId").is(PI)), Document.class, "upfront_payments"); }
    long allRefunded() { return intentRows().stream().flatMap(row -> refundRows(row).stream()).mapToLong(this::amount).sum(); }
    long allCredited() {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("bookingId").in(BOOKING, "inv-booking-b").and("voidedAt").is(null)), Document.class, "pending_charges")
                .stream().mapToLong(row -> Math.max(0, -amount(row))).sum();
    }
    long allReserved() {
        var settled = intentRows().stream().flatMap(row -> refundRows(row).stream()).map(row -> row.getString("providerRef")).toList();
        var commands = mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("providerRef").is(PI).and("kind").regex("^REFUND")), Document.class, "payment_operations");
        var linked = commands.stream().map(op -> op.getString("resultId")).filter(Objects::nonNull).toList();
        return commands.stream().filter(op -> !failed(op) && !settled.contains(op.getString("resultId"))).mapToLong(this::amount).sum()
                + mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("intent").is(PI).and("status").is("pending")), Document.class, "stripe_refunds")
                .stream().filter(row -> !linked.contains(row.getString("_id").substring(CLUB.length() + 1))).mapToLong(this::amount).sum();
    }
    void sharedInvariants() {
        assertThat(allRefunded() + allReserved() + allCredited()).as("one capture allowance, including credits").isLessThanOrEqualTo(intentCaptured);
        for (var row : intentRows()) { assertThat(refundRows(row).stream().mapToLong(this::amount).sum()).isBetween(0L, CAPTURED); }
        for (var refund : stripe.values()) {
            var state = mongo.findById(CLUB + ":" + refund.id(), Document.class, "stripe_refunds");
            long allocated = intentRows().stream().flatMap(row -> refundRows(row).stream()).filter(r -> refund.id().equals(r.getString("providerRef"))).mapToLong(this::amount).sum();
            assertThat(allocated).as("settled allocation for %s", refund.id()).isEqualTo(state != null && "succeeded".equals(state.getString("status")) ? refund.amount() : 0);
        }
    }

    void lateInvariants(String command, boolean drained) {
        var ledger = ledger();
        assertThat(ledger.consistent()).isTrue();
        assertThat(paymentRow().getString("status")).isEqualTo("CANCELLED");
        assertThat(amount(paymentRow(), "amountPaid")).isZero();
        assertThat(paymentRow().get("stripe")).isNull();
        if (failed(operation(command))) {
            assertThat(ledger.due()).isEqualTo(CAPTURED);
            assertThat(interventions()).singleElement().satisfies(entry ->
                    assertThat(amount(entry.get("details", Document.class), "owed")).isEqualTo(CAPTURED));
            assertThat(amount(operation(command), "interventionOwed")).isEqualTo(CAPTURED);
            assertThat(events("UpfrontRefundIntervention")).hasSize(1);
        } else { assertThat(interventions()).isEmpty(); }
        if (drained) {
            assertThat(ledger.reserved()).isZero();
            assertThat(ledger.refunded()).isEqualTo(failed(operation(command)) ? 0 : CAPTURED);
        }
    }

    /** Go through preparation, expiry and the signed late completion, without linking the late intent to a PAID row. */
    String expiredCheckout() throws Exception { return expiredCheckout(true); }
    String expiredCheckout(boolean booking) throws Exception {
        mongo.remove(Query.query(Criteria.where("_id").is(paymentId)), "upfront_payments");
        String session;
        if (booking) {
            session = tenant(() -> transactions.run(() -> checkouts.prepareBooking("inv-member", BOOKING,
                    new UpfrontPayments.Charge("SINGLE_CLASS", null, new Money(CAPTURED, "EUR")), clock.instant().plusSeconds(900))).sessionId());
        } else {
            tenant(() -> transactions.run(() -> { upfrontPayments.create("inv-member", "e8t12-submission",
                    List.of(new UpfrontPayments.Charge("ENTRY_FEE", null, new Money(CAPTURED, "EUR")))); return null; }));
            paymentId = mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("memberId").is("inv-member")), Document.class, "upfront_payments").getString("_id");
            session = ok(admin(keyed(post("/api/v1/checkout-sessions"), Map.of("memberId", "inv-member", "upfrontPaymentIds", List.of(paymentId),
                    "successUrl", "https://" + HOST + "/ok", "cancelUrl", "https://" + HOST + "/ko"))), 201).path("checkoutSessionId").asText();
        }
        paymentId = mongo.findById(session, Document.class, "checkout_sessions").getList("upfrontPaymentIds", String.class).getFirst();
        lateTarget = booking ? paymentId : session;
        tenant(() -> checkouts.expire(session));
        if (!booking) { mongo.updateFirst(Query.query(Criteria.where("_id").is(paymentId)), new Update().set("status", "CANCELLED"), "upfront_payments"); }
        assertThat(mongo.findById(session, Document.class, "checkout_sessions").getString("status")).isEqualTo("EXPIRED");
        assertThat(paymentRow().getString("status")).isEqualTo("CANCELLED");
        assertThat(paymentRow().get("stripe")).isNull();
        fake.refundStatus("pending");
        webhook("evt_e8t12_late_capture", "checkout.session.completed", Map.of("id", "cs_e8t12", "payment_intent", PI,
                "amount_total", CAPTURED, "payment_status", "paid", "metadata", Map.of("operationId", session)));
        assertThat(operations()).singleElement().satisfies(op -> assertThat(op.getString("kind")).isEqualTo("REFUND_LATE"));
        return operations().getFirst().getString("_id");
    }

    void notificationRecipients(String locale) {
        mail.clear();
        admin("bill-refund-admin", CLUB); admin("bill-other-admin", OTHER);
        admin("bill-refund-instructor", CLUB);
        mongo.updateFirst(Query.query(Criteria.where("_id").is("bill-refund-instructor")), new Update().set("roles", List.of("INSTRUCTOR")).set("defaultProfile", "INSTRUCTOR"), "memberships");
        mongo.updateFirst(Query.query(Criteria.where("_id").is("bill-refund-admin")), new Update().set("locale", locale), "accounts");
    }
    void deliverInterventions(int count, String locale, String... amounts) {
        outbox.dispatch(); outbox.dispatch();
        assertThat(events("UpfrontRefundIntervention")).hasSize(count).allSatisfy(event -> {
            assertThat(event.getString("status")).as("outbox failure: %s", event.get("error")).isEqualTo("PUBLISHED");
        });
        var query = Query.query(Criteria.where("code").is("N-55"));
        var notices = mongo.find(query, Document.class, "notifications");
        assertThat(notices).as("admin-visible N-55").hasSize(count);
        tenant(() -> notifications.dispatch(notices.stream().map(n -> n.getString("_id")).toList()));
        tenant(() -> notifications.dispatch(notices.stream().map(n -> n.getString("_id")).toList()));
        assertThat(mongo.find(query, Document.class, "notifications")).allSatisfy(n -> {
            assertThat(n.getString("clubId")).isEqualTo(CLUB);
            assertThat(n.get("recipient", Document.class).getString("accountId")).isEqualTo("bill-refund-admin");
            assertThat(n.getString("audience")).isEqualTo("ADMINS");
            assertThat(n.getString("locale")).isEqualTo(locale);
            assertThat(n.get("action", Document.class).getString("type")).isEqualTo("OPEN_MEMBER");
            assertThat(n.get("subject", Document.class).getString("memberId")).isEqualTo("inv-member");
            assertThat(n.getString("body")).contains("Refund Example");
            assertThat(n.getList("deliveries", Document.class)).extracting(d -> d.getString("channel")).containsExactlyInAnyOrder("APP", "EMAIL");
            assertThat(n.getList("deliveries", Document.class)).allSatisfy(d -> assertThat(d.getString("status")).isIn("SENT", "DELIVERED"));
        });
        assertThat(mail.messages()).hasSize(count).allSatisfy(m -> assertThat(m.to()).isEqualTo("bill-refund-admin@example.test"));
        for (String amount : amounts) { assertThat(notices).anySatisfy(n -> assertThat(n.getString("body")).contains(amount)); }
    }

    String step(Random random, long seed, int step) throws Exception {
        int dice = random.nextInt(100);
        // A pick with nothing to act on falls back to the step that creates it: webhook → execute → admin refund.
        if (dice >= 52 && dice < 74 && stripe.isEmpty()) { dice = 30; }
        if (dice >= 30 && dice < 52 && pendingCommands().isEmpty()) { dice = 0; }
        if (dice < 14) {
            long amount = List.of(200L, 500L, CAPTURED, 1L + random.nextInt((int) CAPTURED)).get(random.nextInt(4));
            adminRefund(amount, "admin-" + seed + "-" + step);
            return "admin refund " + amount;
        }
        if (dice < 26) {
            String policy = random.nextBoolean() ? "REFUND" : "CREDIT";
            boolean beforeConfirmation = random.nextInt(5) == 0;
            tenant(() -> refunds.compensate(paymentId, policy, beforeConfirmation));
            return "cancel " + policy + (beforeConfirmation ? " before confirmation" : "");
        }
        if (dice < 30) { tenant(() -> refunds.lateBooking(paymentId, PI, new Money(CAPTURED, "EUR"))); return "late confirmation"; }
        if (dice < 52) { return execute(random); }
        if (dice < 74) { return webhook(random); }
        if (dice < 80) { return dashboard(random); }
        if (dice < 86) { stripe(!enabled); return enabled ? "enable Stripe" : "disable Stripe"; }
        if (dice < 89) { bill(); return "bill credit"; }
        if (dice < 97) { recovery.recover(); return "recover"; }
        clock.setInstant(clock.instant().plus(Duration.ofHours(25)));
        recovery.recover();
        return "25 h later, recover";
    }

    void adminRefund(long amount, String key) {
        long free = ledger().free();
        String status = paymentRow().getString("status");
        try (var tenant = TenantContext.open(CLUB)) {
            refunds.upfront(paymentId, new Money(amount, "EUR"), "Admin correction", key);
            assertThat(enabled && "PAID".equals(status) && amount <= free).as("accepted %s of free %s", amount, free).isTrue();
        } catch (ApiException rejected) {
            String expected = !enabled ? "PAYMENT_PROVIDER_NOT_ENABLED" : !"PAID".equals(status) ? "INVALID_STATE" : "REFUND_EXCEEDS_PAID";
            assertThat(rejected.getMessage()).isEqualTo(expected);
            if ("REFUND_EXCEEDS_PAID".equals(expected)) { assertThat(amount).isGreaterThan(free); }
        }
    }

    String execute(Random random) {
        var pending = pendingCommands();
        if (pending.isEmpty()) { return "execute nothing"; }
        String id = pending.get(random.nextInt(pending.size())).getString("_id");
        String mode = List.of("pending", "succeeded", "lost response", "rejected", "refused").get(random.nextInt(5));
        String action = execute(id, mode);
        if (action.equals("execute (rejected)") && compensationCommands().stream().anyMatch(op -> op.getString("_id").equals(id))) {
            reached.merge("~ compensation postponed by an outage (E8-T11 point 1)", 1, Integer::sum);
        }
        return action;
    }

    /** Stripe answers {@code mode}: rejected = an outage of the club's access (401/403/429); refused = its content (4xx). */
    String execute(String id, String mode) {
        fake.refundStatus("succeeded".equals(mode) ? "succeeded" : "pending");
        if ("rejected".equals(mode)) { fake.rejectRefund(ErrorCode.PROVIDER_CONFIG_INVALID); }
        if ("refused".equals(mode)) { fake.refuseRefund(ErrorCode.PROVIDER_CONFIG_INVALID); }
        recordRefunds("lost response".equals(mode));
        int unused;
        try (var tenant = TenantContext.open(CLUB)) { refunds.execute(id); }
        catch (RuntimeException retriedLater) { /* The command keeps its checkpoint. */ }
        finally {
            recordRefunds(false);
            // E8-T11 point 6: a disabled Stripe or a command not yet due never calls it; its rejection must not fire later.
            unused = fake.clearRefundRejections();
        }
        return "execute (" + mode + (unused > 0 ? ", not reached)" : ")");
    }

    /** A refund made in Stripe's dashboard: no operation, at most what Stripe still holds of the charge. */
    String dashboard(Random random) throws Exception {
        long left = available();
        if (left <= 0) { return "dashboard nothing"; }
        long amount = random.nextBoolean() ? left : 1L + random.nextInt((int) left);
        var refund = new StripeRefund("re_dashboard_" + (++events), amount, null, new ArrayList<>(List.of(random.nextBoolean() ? "succeeded" : "pending")));
        stripe.put(refund.id(), refund);
        send("evt_inv_" + (++events), refund.id(), refund.current(), random);
        return "dashboard refund " + refund.current();
    }

    String webhook(Random random) throws Exception {
        if (stripe.isEmpty()) { return "webhook nothing"; }
        int dice = random.nextInt(10);
        if (dice < 2 && !deliveries.isEmpty()) {
            var earlier = deliveries.get(random.nextInt(deliveries.size()));
            send(earlier[0], earlier[1], earlier[2], random);
            return "redeliver " + earlier[2];
        }
        var refund = new ArrayList<>(stripe.values()).get(random.nextInt(stripe.size()));
        String status;
        if ("pending".equals(refund.current()) && dice < 8) {
            status = List.of("succeeded", "succeeded", "failed", "canceled").get(random.nextInt(4));
            refund.history().add(status);
        } else if ("succeeded".equals(refund.current()) && dice < 4) {
            status = "failed";
            refund.history().add(status);
        } else {
            status = refund.history().get(random.nextInt(refund.history().size())); // a late copy of any status it went through
        }
        send("evt_inv_" + (++events), refund.id(), status, random);
        return "webhook " + status;
    }

    void drain(Random random) throws Exception {
        stripe(true);
        recordRefunds(false);
        for (int round = 0; round < 30; round++) {
            clock.setInstant(clock.instant().plus(Duration.ofMinutes(6)));
            fake.refundStatus(random.nextBoolean() ? "succeeded" : "pending");
            for (var command : pendingCommands()) {
                try (var tenant = TenantContext.open(CLUB)) { refunds.execute(command.getString("_id")); }
                catch (RuntimeException retriedLater) { /* Next round. */ }
            }
            for (var refund : List.copyOf(stripe.values())) {
                if ("pending".equals(refund.current())) { refund.history().add(random.nextInt(4) == 0 ? "failed" : "succeeded"); }
                if (!processed(refund.id(), refund.current())) { send("evt_inv_" + (++events), refund.id(), refund.current(), random); }
            }
            recovery.recover();
            if (pendingCommands().isEmpty() && stripe.values().stream().allMatch(r -> processed(r.id(), r.current()))
                    && mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("processedAt").is(null)), "stripe_events") == 0) { return; }
        }
        throw new AssertionError("the sequence did not drain");
    }

    void invariants(boolean drained) {
        var payment = paymentRow();
        var ledger = ledger();
        var settled = refundRows(payment).stream().map(row -> row.getString("providerRef")).toList();
        // What an admin was told the member received beyond the capture (a dashboard refund after a billed credit, E8-T11 point 3).
        long overpaid = overpaid();
        assertThat(ledger.refunded() >= 0 && ledger.reserved() >= 0 && ledger.credited() >= 0).as("non-negative: %s", ledger).isTrue();
        assertThat(ledger.refunded() + ledger.credited()).as("refunded plus credited: %s, overpaid %s", ledger, overpaid).isLessThanOrEqualTo(CAPTURED + overpaid);
        // A dashboard refund reserves money too, but can overtake our commands before Stripe refuses their submission.
        if (stripe.values().stream().noneMatch(r -> r.operationId() == null)) { assertThat(ledger.consistent()).as("conservation: %s", ledger).isTrue(); }
        // Our own commands and the credit never give more than the capture; only a dashboard refund can, and then it is reported.
        long committed = stripe.values().stream().filter(r -> r.operationId() != null && !terminalFailure(r.current())).mapToLong(StripeRefund::amount).sum();
        assertThat(committed + ledger.credited()).as("Stripe refunds %s plus credit %s", committed, ledger.credited()).isLessThanOrEqualTo(CAPTURED);
        String refundObligation = payment.getString("refundCompensationReason"), creditObligation = payment.getString("creditCompensationReason");
        if (MONEY_ONLY && creditRow() != null) { creditObligation = "BOOKING_CANCELLED"; }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("bookingId").is(BOOKING)), "pending_charges")).isLessThanOrEqualTo(1);
        var commands = compensationCommands();
        // E8-T11 point 2: one compensation, plus one supplement per other refund that failed (an admin's, or one made in the dashboard).
        long freed = operations().stream().filter(op -> !commands.contains(op) && failed(op)).count()
                + stripe.values().stream().filter(r -> r.operationId() == null && r.history().stream().anyMatch(CapturedPaymentInvariantsIT::terminalFailure)).count();
        assertThat(commands).as("compensation refunds, %s other refunds failed", freed).hasSizeLessThanOrEqualTo((int) (1 + freed));
        var owedNotices = interventions().stream().filter(entry -> "COMPENSATION_INTERVENTION".equals(entry.getString("reason"))).toList();
        if (!owedNotices.isEmpty()) {
            assertThat(amount(payment, "compensationInterventionOwed")).as("persisted outstanding balance").isEqualTo(ledger.due());
        }
        var credit = creditRow();
        if (ledger.credited() > 0 && credit.getString("invoiceId") == null) {
            assertThat(ledger.consistent()).as("unbilled credit includes live reservations: %s", ledger).isTrue();
        }
        if (payment.get("compensationInterventionAt") != null && !MONEY_ONLY) { assertThat(interventions()).as("intervention without telling the admin").isNotEmpty(); }
        if (!MONEY_ONLY) {
            assertThat(refundObligation == null || creditObligation == null).as("one obligation").isTrue();
            if (creditObligation == null) { assertThat(ledger.credited()).as("credit without a CREDIT obligation").isZero(); }
            if (refundObligation == null) { assertThat(commands).as("compensation refunds without a REFUND obligation").isEmpty(); }
        }
        for (var refund : stripe.values()) {
            var seen = processedStatuses(refund.id());
            if (seen.stream().anyMatch(CapturedPaymentInvariantsIT::terminalFailure)) { assertThat(settled).as("failed %s", refund.id()).doesNotContain(refund.id()); }
            else if (seen.contains("succeeded")) { assertThat(settled).as("succeeded %s", refund.id()).contains(refund.id()); }
        }
        assertThat(mongo.count(Query.query(Criteria.where("clubId").is(CLUB).and("outcome").is("FAILED")), "stripe_events")).as("lost reconciliation").isZero();
        if (!drained) { return; }
        assertThat(ledger.reserved()).as("reserved after draining").isZero();
        var intervention = payment.get("compensationInterventionAt");
        if ((refundObligation != null || creditObligation != null) && intervention == null) {
            assertThat(ledger.refunded() + ledger.credited()).as("obligation fulfilled").isEqualTo(CAPTURED);
        }
        if (MONEY_ONLY) { return; }
        if (intervention != null && refundObligation != null) {
            // E8-T11: only a compensation Stripe failed or refused needs an admin; an outage never does, nor a count of supplements.
            assertThat(commands.stream().anyMatch(this::failed)).as("intervention needs a failed compensation").isTrue();
        }
        if (intervention != null && creditObligation != null) {
            assertThat(credit.getString("invoiceId") != null || credit.get("voidedAt") != null)
                    .as("credit intervention needs a credit that can no longer change").isTrue();
        }
    }
    /** What Stripe still holds of the charge: it refuses a refund beyond it. */
    long available() {
        return intentCaptured - stripe.values().stream().filter(r -> !terminalFailure(r.current())).mapToLong(StripeRefund::amount).sum();
    }
    List<Document> interventions() {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("action").is("PAYMENT_REFUND_INTERVENTION").and("details.intervention").is(true)),
                Document.class, "audit_entries");
    }
    long overpaid() {
        return interventions().stream().filter(entry -> "REFUND_OVER_CREDIT".equals(entry.getString("reason")))
                .mapToLong(entry -> amount(entry.get("details", Document.class), "overpaid")).sum();
    }
    long amount(Document details, String key) { return ((Number) details.get(key, Document.class).get("amountMinor")).longValue(); }
    List<String> warnings(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.contains("Refund compensation") && line.contains(paymentId)).toList();
    }
    /** Executes every command (Stripe answers succeeded) and delivers each refund's success. */
    void settleAll() throws Exception {
        fake.refundStatus("succeeded");
        for (var command : pendingCommands()) { tenant(() -> refunds.execute(command.getString("_id"))); }
        for (var refund : List.copyOf(stripe.values())) {
            if ("pending".equals(refund.current())) { refund.history().add("succeeded"); }
            if ("succeeded".equals(refund.current())) { webhook("evt_settle_" + refund.id(), refundObject(refund, "succeeded")); }
        }
    }

    /** Recomputed from the stored documents, independently of {@code PaymentRefunds}. */
    CapturedPaymentLedger ledger() {
        var rows = new ArrayList<>(refundRows(paymentRow()));
        operations().stream().map(op -> op.get("refund", Document.class)).filter(Objects::nonNull).forEach(rows::add);
        var settled = rows.stream().map(row -> row.getString("providerRef")).toList();
        long refunded = rows.stream().mapToLong(this::amount).sum();
        long reserved = operations().stream().filter(op -> op.getString("kind").startsWith("REFUND") && !settled.contains(op.getString("resultId")) && !failed(op))
                .mapToLong(this::amount).sum();
        var linked = operations().stream().map(op -> op.getString("resultId")).filter(Objects::nonNull).toList();
        reserved += mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("intent").is(PI).and("status").is("pending")),
                Document.class, "stripe_refunds").stream().filter(row -> !linked.contains(row.getString("_id").substring(CLUB.length() + 1)))
                .mapToLong(this::amount).sum();
        var credit = creditRow();
        long credited = credit == null || credit.get("voidedAt") != null || amount(credit) >= 0 ? 0 : -amount(credit);
        return new CapturedPaymentLedger(CAPTURED, refunded, reserved, credited);
    }
    boolean failed(Document operation) {
        String refund = operation.getString("refundStatus");
        return refund != null ? terminalFailure(refund) : terminalFailure(operation.getString("providerStatus"));
    }
    static boolean terminalFailure(String status) { return "failed".equals(status) || "canceled".equals(status); }
    List<String> processedStatuses(String refundId) {
        return deliveries.stream().filter(d -> d[1].equals(refundId)).filter(d -> {
            var row = mongo.findById(d[0], Document.class, "stripe_events");
            return row != null && row.get("processedAt") != null;
        }).map(d -> d[2]).distinct().toList();
    }
    boolean processed(String refundId, String status) { return processedStatuses(refundId).contains(status); }
    List<Document> refundRows(Document payment) { return payment.getList("refunds", Document.class, List.of()); }
    long amount(Document row) { return ((Number) row.get("amount", Document.class).get("amountMinor")).longValue(); }
    Document paymentRow() { return mongo.findById(paymentId, Document.class, "upfront_payments"); }
    Document creditRow() { return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("bookingId").is(BOOKING)), Document.class, "pending_charges"); }
    Document operation(String id) { return mongo.findById(id, Document.class, "payment_operations"); }
    List<Document> operations() { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("targetId").in(lateTarget == null ? List.of(paymentId) : List.of(paymentId, lateTarget))), Document.class, "payment_operations"); }
    List<Document> pendingCommands() {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("resultId").is(null).and("processedAt").is(null)), Document.class, "payment_operations");
    }
    List<Document> compensationCommands() {
        return operations().stream().filter(op -> op.getString("key").equals("refund:" + paymentId) || op.getString("key").startsWith("refund:" + paymentId + ":")).toList();
    }

    void recordRefunds(boolean loseResponse) {
        fake.beforeRefundReturn((call, result) -> {
            var request = (Map<?, ?>) call.request();
            stripe.computeIfAbsent(result.id(), id -> new StripeRefund(id, ((Money) request.get("amount")).amountMinor(),
                    (String) request.get("operationId"), new ArrayList<>(List.of(result.status()))));
            if (loseResponse) { throw new IllegalStateException("Lost response"); }
        });
    }
    void terminate(StripeRefund refund, String status, String eventId) throws Exception {
        refund.history().add(status);
        webhook(eventId, refundObject(refund, status));
    }
    void send(String eventId, String refundId, String status, Random random) throws Exception {
        var object = refundObject(stripe.get(refundId), status);
        if (enabled && random.nextInt(4) > 0) { webhook(eventId, object); }
        else { accept(eventId, object); }
        deliveries.add(new String[] {eventId, refundId, status});
    }
    Map<String, Object> refundObject(StripeRefund refund, String status) {
        return Map.of("id", refund.id(), "object", "refund", "payment_intent", PI, "currency", "eur", "amount", refund.amount(), "status", status,
                "reason", "requested_by_customer", "metadata", refund.operationId() == null ? Map.of() : Map.of("operationId", refund.operationId()));
    }
    void webhook(String eventId, Map<String, Object> object) throws Exception {
        String type = terminalFailure((String) object.get("status")) ? "refund.failed" : "refund.updated";
        webhook(eventId, type, object);
    }
    void webhook(String eventId, String type, Map<String, Object> object) throws Exception {
        String body = mapper.writeValueAsString(Map.of("id", eventId, "type", type, "created", clock.instant().getEpochSecond(), "data", Map.of("object", object)));
        var mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        long t = clock.instant().getEpochSecond();
        String signature = "t=" + t + ",v1=" + HexFormat.of().formatHex(mac.doFinal((t + "." + body).getBytes(StandardCharsets.UTF_8)));
        ok(post("/webhooks/stripe/" + CLUB).header("Stripe-Signature", signature).contentType("application/json").content(body), 200);
    }
    /** An event Stripe delivered while the club still had Stripe on, stored and left for a recovery pass (R-12-21's deferred processing). */
    @SuppressWarnings("unchecked")
    void accept(String eventId, Map<String, Object> object) {
        var work = new Document(object);
        work.put("metadata", new Document((Map<String, Object>) object.get("metadata")));
        try (var tenant = TenantContext.open(CLUB)) {
            inbox.receive(new StripeEvent(eventId, CLUB, eventId, "refund.updated", clock.instant(), null, null, "fixture"),
                    new Document("created", clock.instant().getEpochSecond()).append("object", work));
        } catch (org.springframework.dao.DuplicateKeyException redelivered) { /* Stripe redelivered a stored event. */ }
    }
    void stripe(boolean on) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("paymentProviders.STRIPE.enabled", on), "clubs");
        configs.invalidate(CLUB);
        enabled = on;
    }
    void bill() {
        mongo.updateFirst(Query.query(Criteria.where("clubId").is(CLUB).and("bookingId").is(BOOKING).and("invoiceId").is(null)),
                new Update().set("invoiceId", "inv-billed"), "pending_charges");
    }
    void tenant(Runnable work) { try (var tenant = TenantContext.open(CLUB)) { work.run(); } }
    <T> T tenant(Supplier<T> work) { try (var tenant = TenantContext.open(CLUB)) { return work.get(); } }
}
