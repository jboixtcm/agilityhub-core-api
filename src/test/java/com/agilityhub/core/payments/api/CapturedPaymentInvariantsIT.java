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
    private static final String SECRET = "whsec_example_fixture", PAYMENT = "inv-payment", BOOKING = "inv-booking", PI = "pi_invariants";
    private static final long CAPTURED = 1200;
    private static final int SEEDS = 80, STEPS = 24;
    /** Counterfactual runs against the reviewed code, which stores no CREDIT obligation: check only the money, not the markers. */
    private static final boolean MONEY_ONLY = Boolean.getBoolean("e8t09.moneyOnly");
    @Autowired FakePaymentProvider fake;
    @Autowired ProviderSecretVault vault;
    @Autowired PaymentRecovery recovery;
    @Autowired PaymentRefunds refunds;
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
    private boolean enabled;
    private int events;

    @BeforeEach void captured() { reset(); }
    /** The provider bean is shared with the other billing ITs: leave none of this class's hooks behind. */
    @AfterEach void provider() { fake.reset(); }

    void reset() {
        for (String collection : List.of("stripe_refunds", "payment_operations", "stripe_events", "upfront_payments", "pending_charges", "audit_entries", "domain_events", "notifications")) {
            mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection);
        }
        fake.reset(); stripe.clear(); deliveries.clear();
        // Stripe refuses a refund beyond what is left of the charge (the generator's dashboard refunds can leave less than D).
        fake.beforeRefund(call -> {
            if (((Money) ((Map<?, ?>) call.request()).get("amount")).amountMinor() > available()) {
                throw PaymentNotSubmitted.refused(ErrorCode.PROVIDER_CONFIG_INVALID);
            }
        });
        club(CLUB, HOST, "Europe/Madrid", List.of(Module.values()), Map.of("STRIPE", Map.of("enabled", true, "mode", "test",
                "webhookSecretEnc", vault.encrypt(SECRET, CLUB, "STRIPE", "webhookSecretEnc"))));
        enabled = true;
        mongo.insert(new UpfrontPayment(PAYMENT, CLUB, "inv-member", null, "SINGLE_CLASS", null, new Money(CAPTURED, "EUR"), new Money(CAPTURED, "EUR"),
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
                    "~ compensation postponed by an outage (E8-T11 point 1)", "~ credit absorbed a dashboard refund (E8-T11 point 3)");
        }
    }

    @ParameterizedTest(name = "outage {0}")
    @ValueSource(strings = {"PROVIDER_CONFIG_INVALID", "RATE_LIMITED", "PAYMENT_PROVIDER_NOT_ENABLED"})
    void T_12_17_e8t11_point1_outageDefersTheCompensationUntilItPasses(String code) throws Exception {
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
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
                .satisfies(call -> assertThat(call.key()).isEqualTo("refund:" + PAYMENT));
        webhook("evt_p1_outage_over", refundObject(stripe.get(refund), "succeeded"));
        assertThat(paymentRow().getString("status")).isEqualTo("REFUNDED");
        invariants(true);
    }

    @ParameterizedTest(name = "compensation {0}")
    @ValueSource(strings = {"failed at Stripe", "refused by Stripe"})
    void T_12_17_e8t11_point1_aNeededInterventionTellsTheAdminOnce(String how) throws Exception {
        fake.refundStatus("pending");
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
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
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        clock.setInstant(clock.instant().plus(Duration.ofMinutes(6)));
        recovery.recover();
        assertThat(compensationCommands()).hasSize(1);
        assertThat(paymentRow().get("compensationInterventionAt")).isNotNull();
        assertThat(interventions()).singleElement().satisfies(entry -> {
            assertThat(entry.getString("action")).isEqualTo("PAYMENT_REFUND_INTERVENTION");
            assertThat(entry.getString("entityType")).isEqualTo("UpfrontPayment");
            assertThat(entry.getString("entityId")).isEqualTo(PAYMENT);
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
            partials.add(tenant(() -> refunds.upfront(PAYMENT, new Money(100, "EUR"), "Partial", key).id()));
            tenant(() -> refunds.execute(partials.getLast()));
        }
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        for (int i = 0; i < partials.size(); i++) {
            terminate(stripe.get(operation(partials.get(i)).getString("resultId")), "failed", "evt_p2_partial_" + i);
        }
        // One supplement per failed admin refund, however many: no compensation failed, so no intervention.
        assertThat(compensationCommands()).extracting(op -> op.getString("key")).containsExactlyInAnyOrder("refund:" + PAYMENT,
                "refund:" + PAYMENT + ":1", "refund:" + PAYMENT + ":2", "refund:" + PAYMENT + ":3", "refund:" + PAYMENT + ":4");
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
        tenant(() -> refunds.compensate(PAYMENT, "CREDIT", false));
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
            tenant(() -> refunds.compensate(PAYMENT, "CREDIT", false));
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
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        String command = compensationCommands().getFirst().getString("_id");
        // The state a refund.failed webhook leaves on the compensation, without that webhook's own transaction.
        mongo.updateFirst(Query.query(Criteria.where("_id").is(command)), new Update().set("resultId", "re_p5").set("processedAt", clock.instant())
                .set("refundStatus", "failed"), "payment_operations");
        // Rolled back: nothing was saved, so nothing is reported.
        assertThatThrownBy(() -> tenant(() -> transactions.<Void>run(() -> {
            refunds.compensate(PAYMENT, "REFUND", false);
            throw new IllegalStateException("rolled back");
        }))).hasMessage("rolled back");
        assertThat(paymentRow().get("compensationInterventionAt")).isNull();
        assertThat(warnings(output)).isEmpty();
        // A write conflict runs the whole transaction again: only the attempt that commits reports, once.
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        tenant(() -> transactions.<Void>run(() -> {
            refunds.compensate(PAYMENT, "REFUND", false);
            if (attempts.incrementAndGet() == 1) { throw new org.springframework.dao.DuplicateKeyException("write conflict"); }
            return null;
        }));
        assertThat(attempts).hasValue(2);
        assertThat(paymentRow().get("compensationInterventionAt")).isNotNull();
        assertThat(interventions()).hasSize(1);
        assertThat(warnings(output)).singleElement().asString().contains("WARN", "paymentId=" + PAYMENT);
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        assertThat(warnings(output)).hasSize(1);
    }

    @Test void T_12_17_e8t11_point6_aRejectionStripeNeverReceivedDoesNotReachALaterCall() {
        fake.refundStatus("pending");
        String first = tenant(() -> refunds.upfront(PAYMENT, new Money(200, "EUR"), "Partial", "p6-first").id());
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
        String partial = tenant(() -> refunds.upfront(PAYMENT, new Money(200, "EUR"), "Partial", "f1-partial").id());
        tenant(() -> refunds.execute(partial));
        tenant(() -> refunds.compensate(PAYMENT, "CREDIT", false));
        tenant(() -> refunds.compensate(PAYMENT, "CREDIT", false));
        assertThat(amount(creditRow())).isEqualTo(-1000);
        if (billed) { bill(); }
        terminate(stripe.get(operation(partial).getString("resultId")), "failed", "evt_f1_failed");
        tenant(() -> refunds.compensate(PAYMENT, "CREDIT", false));
        tenant(() -> refunds.lateBooking(PAYMENT, PI, new Money(CAPTURED, "EUR")));
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
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        String command = compensationCommands().getFirst().getString("_id");
        tenant(() -> refunds.execute(command));
        var refund = stripe.get(operation(command).getString("resultId"));
        terminate(refund, status, "evt_f2_terminal");
        webhook("evt_f2_redelivered", refundObject(refund, status));
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        tenant(() -> refunds.lateBooking(PAYMENT, PI, new Money(CAPTURED, "EUR")));
        recovery.recover();
        assertThat(compensationCommands()).hasSize(1);
        assertThat(fake.calls().stream().filter(call -> call.operation().equals("refund"))).hasSize(1);
        assertThat(paymentRow().get("compensationInterventionAt")).isNotNull();
        assertThat(ledger().free()).isEqualTo(CAPTURED);
        invariants(false);
    }

    @Test void T_12_17_round5_finding3_acceptedFailureReconcilesWhileStripeIsDisabled() throws Exception {
        fake.refundStatus("pending");
        String partial = tenant(() -> refunds.upfront(PAYMENT, new Money(200, "EUR"), "Partial", "f3-partial").id());
        tenant(() -> refunds.execute(partial));
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        String compensation = compensationCommands().getFirst().getString("_id");
        tenant(() -> refunds.execute(compensation));
        var failed = stripe.get(operation(partial).getString("resultId"));
        failed.history().add("failed");
        accept("evt_f3_failed", refundObject(failed, "failed"));
        stripe(false);
        recovery.recover();
        assertThat(mongo.findById("evt_f3_failed", Document.class, "stripe_events").getString("outcome")).isEqualTo("PROCESSED");
        assertThat(failed(operation(partial))).isTrue();
        var supplement = compensationCommands().stream().filter(op -> op.getString("key").equals("refund:" + PAYMENT + ":1")).findFirst().orElseThrow();
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
        String partial = tenant(() -> refunds.upfront(PAYMENT, new Money(200, "EUR"), "Partial", "r2-p1").id());
        if (submitted) { tenant(() -> refunds.execute(partial)); }
        tenant(() -> refunds.compensate(PAYMENT, "CREDIT", false));
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
            tenant(() -> refunds.compensate(PAYMENT, "CREDIT", false)); bill();
            var dashboard = new StripeRefund("re_r2_p2_dashboard", 500, null, new ArrayList<>(List.of("succeeded")));
            stripe.put(dashboard.id(), dashboard);
            webhook("evt_r2_p2", refundObject(dashboard, "succeeded"));
            webhook("evt_r2_p2_copy", refundObject(dashboard, "succeeded"));
        } else {
            fake.refundStatus("pending");
            tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
            String command = compensationCommands().getFirst().getString("_id");
            tenant(() -> refunds.execute(command));
            var failed = stripe.get(operation(command).getString("resultId"));
            terminate(failed, "failed", "evt_r2_p2");
            webhook("evt_r2_p2_copy", refundObject(failed, "failed"));
        }
        tenant(() -> refunds.compensate(PAYMENT, billed ? "CREDIT" : "REFUND", false));
        deliverInterventions(1, locale, billed ? "5" : "12");
        assertThat(interventions()).singleElement().satisfies(entry ->
                assertThat(entry.getString("action")).isEqualTo("PAYMENT_REFUND_INTERVENTION"));
        assertThat(events("UpfrontRefundIntervention")).singleElement().satisfies(event -> {
            var payload = event.get("payload", Document.class);
            assertThat(payload.getString("paymentId")).isEqualTo(PAYMENT);
            assertThat(payload.getString("memberId")).isEqualTo("inv-member");
            assertThat(payload.getString("reason")).isEqualTo(billed ? "REFUND_OVER_CREDIT" : "COMPENSATION_INTERVENTION");
            assertThat(amount(payload, "amount")).isEqualTo(billed ? 500 : CAPTURED);
        });
    }

    @Test void T_12_17_e8t11_round2_point3_laterFailureUpdatesTheWholeOwedBalance() throws Exception {
        notificationRecipients("ca");
        fake.refundStatus("pending");
        String partial = tenant(() -> refunds.upfront(PAYMENT, new Money(200, "EUR"), "Partial", "r2-p3").id());
        tenant(() -> refunds.execute(partial));
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        String command = compensationCommands().getFirst().getString("_id");
        tenant(() -> refunds.execute(command));
        terminate(stripe.get(operation(command).getString("resultId")), "failed", "evt_r2_p3_compensation");
        assertThat(interventions()).extracting(e -> amount(e.get("details", Document.class), "owed")).containsExactly(1000L);
        var failed = stripe.get(operation(partial).getString("resultId"));
        terminate(failed, "failed", "evt_r2_p3_partial");
        webhook("evt_r2_p3_duplicate", refundObject(failed, "failed"));
        tenant(() -> refunds.compensate(PAYMENT, "REFUND", false));
        assertThat(interventions()).extracting(e -> amount(e.get("details", Document.class), "owed")).containsExactly(1000L, 1200L);
        assertThat(amount(paymentRow(), "compensationInterventionOwed")).isEqualTo(1200);
        deliverInterventions(2, "ca", "10", "12");
        assertThat(compensationCommands()).hasSize(1);
        // The admin's new reservation and its success reduce the persisted balance to zero, with no new demand.
        String repayment = tenant(() -> refunds.upfront(PAYMENT, new Money(CAPTURED, "EUR"), "Repayment", "r2-p3-repay").id());
        assertThat(amount(paymentRow(), "compensationInterventionOwed")).isZero();
        tenant(() -> refunds.execute(repayment));
        terminate(stripe.get(operation(repayment).getString("resultId")), "succeeded", "evt_r2_p3_repaid");
        assertThat(interventions()).hasSize(2);
        invariants(true);
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void T_12_17_e8t11_round2_point4_billedCreditWarnsOnceOnlyAfterCommit(CapturedOutput output) throws Exception {
        tenant(() -> refunds.compensate(PAYMENT, "CREDIT", false)); bill();
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
        assertThat(warnings(output)).singleElement().asString().contains("WARN", "paymentId=" + PAYMENT);
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
            tenant(() -> refunds.compensate(PAYMENT, policy, beforeConfirmation));
            return "cancel " + policy + (beforeConfirmation ? " before confirmation" : "");
        }
        if (dice < 30) { tenant(() -> refunds.lateBooking(PAYMENT, PI, new Money(CAPTURED, "EUR"))); return "late confirmation"; }
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
            refunds.upfront(PAYMENT, new Money(amount, "EUR"), "Admin correction", key);
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
        // A dashboard refund is outside the reservations: until Stripe refuses the commands it outran, only it may overcommit them.
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
        return CAPTURED - stripe.values().stream().filter(r -> !terminalFailure(r.current())).mapToLong(StripeRefund::amount).sum();
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
        return output.getOut().lines().filter(line -> line.contains("Refund compensation") && line.contains(PAYMENT)).toList();
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
        var rows = refundRows(paymentRow());
        var settled = rows.stream().map(row -> row.getString("providerRef")).toList();
        long refunded = rows.stream().mapToLong(this::amount).sum();
        long reserved = operations().stream().filter(op -> op.getString("kind").startsWith("REFUND") && !settled.contains(op.getString("resultId")) && !failed(op))
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
    Document paymentRow() { return mongo.findById(PAYMENT, Document.class, "upfront_payments"); }
    Document creditRow() { return mongo.findOne(Query.query(Criteria.where("clubId").is(CLUB).and("bookingId").is(BOOKING)), Document.class, "pending_charges"); }
    Document operation(String id) { return mongo.findById(id, Document.class, "payment_operations"); }
    List<Document> operations() { return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("targetId").is(PAYMENT)), Document.class, "payment_operations"); }
    List<Document> pendingCommands() {
        return mongo.find(Query.query(Criteria.where("clubId").is(CLUB).and("resultId").is(null).and("processedAt").is(null)), Document.class, "payment_operations");
    }
    List<Document> compensationCommands() {
        return operations().stream().filter(op -> op.getString("key").equals("refund:" + PAYMENT) || op.getString("key").startsWith("refund:" + PAYMENT + ":")).toList();
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
