package com.agilityhub.core.configuration;

import com.agilityhub.core.clubs.census.domain.*;
import com.agilityhub.core.clubs.census.persistence.CensusRepository;
import com.agilityhub.core.clubs.census.persistence.LifecycleParts;
import com.agilityhub.core.clubs.census.persistence.Member;
import com.agilityhub.core.clubs.census.persistence.inactivity.*;
import com.agilityhub.core.clubs.census.persistence.leave.*;
import com.agilityhub.core.payments.domain.*;
import com.agilityhub.core.payments.persistence.*;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.BillingDocuments.*;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.HostTenantResolver;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.platform.application.definition.ClubDefinitionMapper;
import com.agilityhub.core.platform.persistence.Club;
import com.agilityhub.core.platform.persistence.ClubRepository;
import com.agilityhub.core.platform.support.PlatformFixtures;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.support.AbstractIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * E8-T01 step 5: the S12/S13 documents with their indexes — including the three guards E8's concurrency relies on (unique
 * `{clubId, series, number}` on `invoices`, partial unique live `{clubId, period}` on `billing_runs`, unique `{eventId}` on
 * `stripe_events`) —, the widened `upfront_payments`, `Member.leaveHistory` and `paymentMethod.card.invalid`, `CLUB.billing`
 * kept by every full save of the club and by `club:apply`, and the Stripe secrets absent from every club projection.
 */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class E8PersistenceIT extends AbstractIntegrationTest {
    static final String CLUB = "e8-persistence-a", OTHER = "e8-persistence-b", HOST = "e8p-a.example.test";
    static final List<String> COLLECTIONS = List.of("invoices", "collections", "remittances", "billing_runs", "billing_simulations", "pack_balances", "pending_charges",
            "stripe_events", "billing_locks", "inactivity_periods", "leave_requests");
    @Autowired MongoTemplate mongo;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ClubRepository clubs;
    @Autowired ClubConfigService configs;
    @Autowired HostTenantResolver hosts;
    @Autowired ClubDefinitionMapper definitions;
    @Autowired InvoiceRepository invoices;
    @Autowired CollectionRepository collections;
    @Autowired RemittanceRepository remittances;
    @Autowired BillingRunRepository runs;
    @Autowired BillingSimulationRepository simulations;
    @Autowired PackBalanceRepository packs;
    @Autowired PendingChargeRepository pendingCharges;
    @Autowired StripeEventRepository stripeEvents;
    @Autowired BillingLockRepository locks;
    @Autowired UpfrontPaymentRepository upfront;
    @Autowired InactivityPeriodRepository periods;
    @Autowired LeaveRequestRepository requests;
    @Autowired CensusRepository<Member> members;

    @BeforeEach void clean() {
        TenantContext.clear();
        for (String collection : COLLECTIONS) { mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), collection); }
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "upfront_payments");
        mongo.remove(Query.query(Criteria.where("clubId").in(CLUB, OTHER)), "members");
        for (String clubId : List.of(CLUB, OTHER)) {
            mongo.remove(Query.query(Criteria.where("_id").is(clubId)), Club.class);
            var tree = (ObjectNode) mapper.valueToTree(PlatformFixtures.club(clubId, clubId.equals(CLUB) ? HOST : "e8p-b.example.test"));
            tree.set("modules", mapper.valueToTree(Module.values()));
            clubs.save(mapper.convertValue(tree, Club.class)); configs.invalidate(clubId);
        }
        hosts.invalidate();
    }
    private Map<String, Document> indexes(String collection) {
        var result = new LinkedHashMap<String, Document>();
        for (Document index : mongo.getCollection(collection).listIndexes()) { result.put(index.getString("name"), index); }
        return result;
    }
    private static Document keys(Object... pairs) {
        var keys = new Document();
        for (int i = 0; i < pairs.length; i += 2) { keys.append((String) pairs[i], pairs[i + 1]); }
        return keys;
    }

    @Test void T_12_10_T_12_11_T_12_15_theElevenCollectionsDeclareTheirIndexesAndTheThreeConcurrencyGuards() {
        for (String collection : COLLECTIONS) {
            // Evidence (task «Evidence to return»): the index listing of each new collection, as mongosh's getIndexes() shows it.
            indexes(collection).values().forEach(index -> System.out.println("E8-T01 index " + collection + " " + index.toJson()));
        }
        var invoice = indexes("invoices");
        assertThat(invoice.get("invoice_club_series_number").get("key")).isEqualTo(keys("clubId", 1, "series", 1, "number", 1));
        assertThat(invoice.get("invoice_club_series_number").getBoolean("unique")).isTrue();
        assertThat(invoice.get("invoice_club_period_status").get("key")).isEqualTo(keys("clubId", 1, "period", 1, "status", 1));
        assertThat(invoice.get("invoice_club_member_issue").get("key")).isEqualTo(keys("clubId", 1, "memberId", 1, "issueDate", -1));
        assertThat(invoice.get("invoice_club_run").get("key")).isEqualTo(keys("clubId", 1, "runId", 1));
        assertThat(invoice.get("invoice_club_remittance").get("key")).isEqualTo(keys("clubId", 1, "remittanceId", 1));
        var collection = indexes("collections");
        assertThat(collection.get("collection_club_invoice_attempt").get("key")).isEqualTo(keys("clubId", 1, "invoiceId", 1, "attempt", 1));
        assertThat(collection.get("collection_club_provider_status").get("key")).isEqualTo(keys("clubId", 1, "provider", 1, "status", 1));
        assertThat(collection.get("collection_club_remittance").get("key")).isEqualTo(keys("clubId", 1, "remittanceId", 1));
        assertThat(collection.get("collection_club_provider_ref").getBoolean("unique")).isTrue();
        assertThat(collection.get("collection_club_provider_ref").get("partialFilterExpression")).isEqualTo(new Document("providerRef", new Document("$type", "string")));
        var remittance = indexes("remittances");
        assertThat(remittance.get("remittance_club_period_status").get("key")).isEqualTo(keys("clubId", 1, "period", 1, "status", 1));
        assertThat(remittance.get("remittance_club_message").get("key")).isEqualTo(keys("clubId", 1, "messageId", 1));
        assertThat(remittance.get("remittance_club_message").getBoolean("unique")).isTrue();
        var run = indexes("billing_runs").get("billing_run_live_period");
        assertThat(run.get("key")).isEqualTo(keys("clubId", 1, "period", 1));
        assertThat(run.getBoolean("unique")).isTrue();
        assertThat(run.get("partialFilterExpression")).isEqualTo(new Document("status", new Document("$in", List.of("GENERATED", "CHARGING", "COMPLETED"))));
        assertThat(indexes("billing_simulations").get("billing_simulation_club_period").getBoolean("unique")).isTrue();
        var pack = indexes("pack_balances");
        assertThat(pack.get("pack_club_dog_state_expiry").get("key")).isEqualTo(keys("clubId", 1, "dogId", 1, "state", 1, "expiresOn", 1));
        assertThat(pack.get("pack_club_member_state").get("key")).isEqualTo(keys("clubId", 1, "memberId", 1, "state", 1));
        assertThat(pack.get("pack_club_upfront_payment").getBoolean("unique")).isTrue();
        var charge = indexes("pending_charges");
        assertThat(charge.get("pending_charge_club_booking").getBoolean("unique")).isTrue();
        assertThat(charge.get("pending_charge_club_member_invoice").get("key")).isEqualTo(keys("clubId", 1, "memberId", 1, "invoiceId", 1));
        var stripe = indexes("stripe_events");
        assertThat(stripe.get("stripe_event_id").get("key")).isEqualTo(keys("eventId", 1));
        assertThat(stripe.get("stripe_event_id").getBoolean("unique")).isTrue();
        assertThat(stripe.get("stripe_event_club_received").get("key")).isEqualTo(keys("clubId", 1, "receivedAt", 1));
        var lock = indexes("billing_locks").get("billing_lock_ttl");
        assertThat(lock.get("key")).isEqualTo(keys("expiresAt", 1)); assertThat(lock.get("expireAfterSeconds", Number.class).longValue()).isZero();
        assertThat(BillingDocuments.BILLING_LOCK_LEASE).hasMinutes(10);
        var inactivity = indexes("inactivity_periods");
        assertThat(inactivity.get("inactivity_club_member_state").get("key")).isEqualTo(keys("clubId", 1, "memberId", 1, "state", 1));
        assertThat(inactivity.get("inactivity_club_state_from").get("key")).isEqualTo(keys("clubId", 1, "state", 1, "fromMonth", 1));
        var leave = indexes("leave_requests");
        assertThat(leave.get("leave_club_member_state").get("key")).isEqualTo(keys("clubId", 1, "memberId", 1, "state", 1));
        assertThat(leave.get("leave_club_state_requested").get("key")).isEqualTo(keys("clubId", 1, "state", 1, "requestedDate", 1));
        assertThat(leave.get("leave_club_source_state").get("key")).isEqualTo(keys("clubId", 1, "source", 1, "state", 1));
        assertThat(indexes("upfront_payments").get("upfront_club_member_status").get("key")).isEqualTo(keys("clubId", 1, "memberId", 1, "status", 1));
    }

    private static Invoice invoice(String id, String clubId, long number, Instant now) {
        Money fee = new Money(6000, "EUR"), zero = new Money(0, "EUR");
        return new Invoice(id, clubId, "2026", number, String.format("2026-%04d", number), "2026-08-25", "2026-09", "e8p-member-a",
                new Invoice.MemberSnapshot(214, "Laura Example", null), List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "e8p-price", null,
                "Quota Abonat — Setembre 2026", fee, BigDecimal.ZERO, zero, fee)), fee, zero, fee,
                new Invoice.PaymentMethodSnapshot(PaymentMethodType.SEPA_DD, "···· ···· ···· ···· 1234", "Laura Example", "e8p-214-1", null, null),
                InvoiceStatus.PENDING, InvoiceKind.PERIODIC, "e8p-run", null, false, null, null, null, null, null, null, zero, null, null, now, "e8p-admin", now, "e8p-admin");
    }
    private static BillingRun run(String id, String clubId, BillingRunStatus status, Instant now) {
        return new BillingRun(id, clubId, "2026-09", status, "e8p-simulation", List.of(), new BillingRun.ByProvider(null, null, null), "2026-09-01", now, null,
                List.of(), List.of(), 1, "e8p-admin", null, null, null, now);
    }

    @Test void T_12_10_T_12_11_T_12_13_T_12_15_theGuardsHoldAndTheDocumentsRoundTripInTheirTenant() {
        Instant now = clock.instant();
        try (var tenant = TenantContext.open(CLUB)) {
            // R-12-08: a number is never issued twice in a series; another series or club may reuse it.
            var first = invoices.insert(invoice("e8p-invoice-1", CLUB, 912, now));
            assertThat(invoices.findById("e8p-invoice-1")).contains(first);
            assertThatThrownBy(() -> invoices.insert(invoice("e8p-invoice-2", CLUB, 912, now))).isInstanceOf(DuplicateKeyException.class);
            // R-12-11: one live run per club and period; a rolled-back run leaves the month free again.
            runs.insert(run("e8p-run-1", CLUB, BillingRunStatus.GENERATED, now));
            assertThatThrownBy(() -> runs.insert(run("e8p-run-2", CLUB, BillingRunStatus.COMPLETED, now))).isInstanceOf(DuplicateKeyException.class);
            mongo.updateFirst(Query.query(Criteria.where("_id").is("e8p-run-1")), new Update().set("status", "ROLLED_BACK"), BillingRun.class);
            runs.insert(run("e8p-run-3", CLUB, BillingRunStatus.GENERATED, now));
            runs.insert(run("e8p-run-4", CLUB, BillingRunStatus.ROLLED_BACK, now));
            assertThat(runs.findById("e8p-run-3")).get().extracting(BillingRun::status).isEqualTo(BillingRunStatus.GENERATED);
            // R-12-21: one stored Stripe event per eventId, over every club.
            stripeEvents.insert(new StripeEvent("e8p-stripe-1", CLUB, "evt_e8p_1", "payment_intent.succeeded", now, null, null, "e8p-hash"));
            // R-12-07: the month keeps one simulation.
            simulations.insert(new BillingSimulation("e8p-simulation-1", CLUB, "2026-09", now, List.of(), List.of(), List.of(), null, "e8p-admin"));
            assertThatThrownBy(() -> simulations.insert(new BillingSimulation("e8p-simulation-2", CLUB, "2026-09", now, List.of(), List.of(), List.of(), null, "e8p-admin")))
                    .isInstanceOf(DuplicateKeyException.class);
            // R-12-12: a remittance's MsgId is unique in the club.
            var creditor = new Remittance.Creditor("Club Example", "ES00ZZZB00000000", "ES0000000000000000000000", null);
            remittances.insert(new Remittance("e8p-remittance-1", CLUB, "e8p-run-3", "2026-09", "e8p-2026-09-1", now, "2026-09-01", creditor, List.of(), 0,
                    new Money(0, "EUR"), new Remittance.SequenceBreakdown(0, 0), null, now, null, RemittanceStatus.GENERATED, null, null, null, now, "e8p-admin"));
            assertThatThrownBy(() -> remittances.insert(new Remittance("e8p-remittance-2", CLUB, "e8p-run-3", "2026-09", "e8p-2026-09-1", now, "2026-09-01", creditor,
                    List.of(), 0, new Money(0, "EUR"), new Remittance.SequenceBreakdown(0, 0), null, now, null, RemittanceStatus.GENERATED, null, null, null, now, null)))
                    .isInstanceOf(DuplicateKeyException.class);
            // R-12-21/13: a provider reference once; collections without one never collide.
            for (String id : List.of("e8p-collection-1", "e8p-collection-2")) {
                collections.insert(new Collection(id, CLUB, "e8p-invoice-1", CollectionProvider.MANUAL, new Money(6000, "EUR"), CollectionStatus.CREATED, null, null,
                        1, null, null, List.of(), now, null));
            }
            collections.insert(new Collection("e8p-collection-3", CLUB, "e8p-invoice-1", CollectionProvider.STRIPE, new Money(6000, "EUR"), CollectionStatus.SUBMITTED,
                    "pi_e8p_1", null, 2, null, null, List.of(), now, null));
            assertThatThrownBy(() -> collections.insert(new Collection("e8p-collection-4", CLUB, "e8p-invoice-1", CollectionProvider.STRIPE, new Money(6000, "EUR"),
                    CollectionStatus.SUBMITTED, "pi_e8p_1", null, 3, null, null, List.of(), now, null))).isInstanceOf(DuplicateKeyException.class);
            assertThat(collections.forInvoice("e8p-invoice-1")).extracting(Collection::attempt).containsExactly(1, 1, 2);
            // R-12-23: a payment opens one pack; packs opened by hand (no payment) never collide.
            for (String id : List.of("e8p-pack-1", "e8p-pack-2")) { packs.insert(pack(id, null, now)); }
            packs.insert(pack("e8p-pack-3", "e8p-upfront-1", now));
            assertThatThrownBy(() -> packs.insert(pack("e8p-pack-4", "e8p-upfront-1", now))).isInstanceOf(DuplicateKeyException.class);
            // R-12-25: one pending charge per booking.
            pendingCharges.insert(new PendingCharge("e8p-charge-1", CLUB, "e8p-member-a", "e8p-dog", "e8p-booking-1", "e8p-price", new Money(1200, "EUR"),
                    "Classe 06/10 — Duna", now, null, null));
            assertThatThrownBy(() -> pendingCharges.insert(new PendingCharge("e8p-charge-2", CLUB, "e8p-member-a", "e8p-dog", "e8p-booking-1", "e8p-price",
                    new Money(1200, "EUR"), "Classe 06/10 — Duna", now, null, null))).isInstanceOf(DuplicateKeyException.class);
            locks.insert(new BillingLock(BillingLock.idFor(CLUB), CLUB, "e8p-holder", now, now.plus(BillingDocuments.BILLING_LOCK_LEASE)));
            assertThat(locks.findById(CLUB + ":billing")).get().extracting(BillingLock::holder).isEqualTo("e8p-holder");
            // S13 §3: periods and requests, and the own-member lookups of R-13-18.
            var period = new InactivityPeriod("e8p-period-1", CLUB, "e8p-member-a", "2026-11", null, null, InactivityState.APPROVED, LifecycleOrigin.BACKOFFICE, now,
                    new LifecycleParts.Requester("e8p-admin", "e8p-member-a"), new InactivityPeriod.Decision(now, "e8p-admin", LifecycleDecision.APPROVED, null, true),
                    new InactivityPeriod.FeeSnapshot(new Money(2000, "EUR"), new Money(1000, "EUR")), null, null, null, null, null, null,
                    List.of(new LifecycleParts.CancelledBooking(CancelledBookingType.CLASS, "e8p-booking-1", "2026-11-03")),
                    List.of(new InactivityPeriod.HistoryEntry(now, "e8p-admin", "2026-11", null, ChangeSource.ADMIN)), null, now, now);
            periods.insert(period);
            assertThat(periods.findById("e8p-period-1")).get().usingRecursiveComparison().ignoringFields("version").isEqualTo(period);
            assertThat(periods.findOwn("e8p-period-1", "e8p-member-a")).isPresent(); assertThat(periods.findOwn("e8p-period-1", "e8p-member-b")).isEmpty();
            var request = new LeaveRequest("e8p-leave-1", CLUB, "e8p-member-a", LeaveSource.PACK_EXPIRED, LifecycleOrigin.SYSTEM, now, new LifecycleParts.Requester("system", null),
                    "2026-12-12", "PACK_EXPIRED", null, null, LeaveRequestState.APPROVED,
                    new LeaveRequest.Decision(now, "system", LifecycleDecision.APPROVED, "2026-12-12", null), null, null, null, null, List.of(), "e8p-pack-3", null, now, now);
            requests.insert(request);
            assertThat(requests.findOwn("e8p-leave-1", "e8p-member-a")).get().usingRecursiveComparison().ignoringFields("version").isEqualTo(request);
            assertThat(requests.findOwn("e8p-leave-1", "e8p-member-b")).isEmpty();
        }
        try (var tenant = TenantContext.open(OTHER)) {
            // Another club reuses the number, the period's live run and the booking, and sees none of club A's documents.
            invoices.insert(invoice("e8p-invoice-9", OTHER, 912, now));
            runs.insert(run("e8p-run-9", OTHER, BillingRunStatus.GENERATED, now));
            pendingCharges.insert(new PendingCharge("e8p-charge-9", OTHER, "e8p-member-a", "e8p-dog", "e8p-booking-1", "e8p-price", new Money(1200, "EUR"), "Classe", now, null, null));
            assertThatThrownBy(() -> stripeEvents.insert(new StripeEvent("e8p-stripe-9", OTHER, "evt_e8p_1", "payment_intent.succeeded", now, null, null, "e8p-hash")))
                    .as("Stripe's event ids are global").isInstanceOf(DuplicateKeyException.class);
            assertThat(invoices.findById("e8p-invoice-1")).isEmpty(); assertThat(periods.findById("e8p-period-1")).isEmpty();
            assertThat(requests.findById("e8p-leave-1")).isEmpty(); assertThat(packs.findAll()).isEmpty();
            assertThatThrownBy(() -> invoices.insert(invoice("e8p-invoice-8", CLUB, 1, now))).isInstanceOf(ApiException.class);
        }
    }
    private static PackBalance pack(String id, String upfrontPaymentId, Instant now) {
        return new PackBalance(id, CLUB, "e8p-member-a", "e8p-dog", "e8p-plan", upfrontPaymentId, 10, 0, 10, "2026-06-12", "2026-11-11", PackBalanceState.ACTIVE,
                List.of(new PackBalance.Movement("e8p-movement", PackMovementType.OPEN, 10, null, null, null, now)), null, null, null, null, null, now, "e8p-admin");
    }

    /**
     * S12 §3 `UpfrontPayment` widened without breaking the signup flow: a row written by the S04 flow (no S12 field) loads
     * with the new fields empty, the signup's partial update keeps the S12 ones, and a full S12 row round-trips.
     */
    @Test void WP_12_A_upfrontPaymentsWidenWithoutBreakingTheSignupRows() {
        Instant now = clock.instant();
        try (var tenant = TenantContext.open(CLUB)) {
            var signup = upfront.insert(new UpfrontPayment("e8p-upfront-signup", CLUB, "e8p-member-a", null, "ENTRY_FEE", "ENTRY_FEE", new Money(10000, "EUR"),
                    new Money(0, "EUR"), "DUE", null, null, now, null, null, "e8p-submission", null));
            assertThat(upfront.findById("e8p-upfront-signup")).contains(signup);
            assertThat(signup.refunds()).isNull(); assertThat(signup.stripe()).isNull(); assertThat(signup.packBalanceId()).isNull();
            var full = new UpfrontPayment("e8p-upfront-pack", CLUB, "e8p-member-a", "e8p-dog", "PACK", null, new Money(12000, "EUR"), new Money(12000, "EUR"), "PAID",
                    "STRIPE", "e8p-checkout", now, now, null, null, null, null, "e8p-pack-3", new UpfrontPayment.StripeRefs("pi_e8p_2", "ch_e8p_2"), null, null,
                    List.of(new UpfrontPayment.Refund(new Money(6000, "EUR"), "re_e8p_2", now, "Error", "e8p-admin")), "Pack regalat");
            upfront.insert(full);
            // The signup flow's update sets only its own fields.
            upfront.update(new UpfrontPayment("e8p-upfront-pack", CLUB, "e8p-member-a", "e8p-dog", "PACK", null, new Money(12000, "EUR"), new Money(12000, "EUR"),
                    "REFUNDED", "STRIPE", "e8p-checkout", now, now, null, null, null));
            var stored = upfront.findById("e8p-upfront-pack").orElseThrow();
            assertThat(stored.status()).isEqualTo("REFUNDED");
            assertThat(stored.stripe()).isEqualTo(full.stripe()); assertThat(stored.refunds()).isEqualTo(full.refunds());
            assertThat(stored.packBalanceId()).isEqualTo("e8p-pack-3"); assertThat(stored.note()).isEqualTo("Pack regalat");
            assertThat(upfront.member("e8p-member-a")).hasSize(2);
        }
    }

    /** S13 §3 `Member.leaveHistory[]` and S12 R-12-22 `paymentMethod.card.invalid` are stored and kept by the census writes. */
    @Test void T_13_21_T_12_16_theMemberKeepsItsLeaveHistoryAndTheCardInvalidMark() {
        Instant now = clock.instant();
        mongo.save(new Document("_id", "e8p-member-a").append("clubId", CLUB).append("status", "ACTIVE").append("firstName", "Laura").append("lastName1", "Example")
                .append("paymentMethod", new Document("type", "CARD").append("card", new Document("last4", "4242").append("brand", "visa").append("invalid", true)))
                .append("leftReason", "LEAVE_REQUEST").append("version", 0), "members");
        try (var tenant = TenantContext.open(CLUB)) {
            var member = members.require("e8p-member-a");
            assertThat(member.paymentMethod).containsEntry("type", "CARD");
            assertThat(new LinkedHashMap<Object, Object>((Map<?, ?>) member.paymentMethod.get("card"))).containsEntry("invalid", true);
            assertThat(MemberLeftReason.valueOf(member.leftReason)).isEqualTo(MemberLeftReason.LEAVE_REQUEST);
            member.leaveHistory = List.of(Map.of("leaveDate", "2025-08-31", "leftAt", Date.from(now), "leftReason", "LEAVE_REQUEST", "reactivatedAt", Date.from(now)));
            member.status = "ACTIVE"; member.leftReason = null;
            members.save(member);
            var stored = members.require("e8p-member-a");
            assertThat(stored.leaveHistory).singleElement().satisfies(entry -> assertThat(entry).containsEntry("leftReason", "LEAVE_REQUEST").containsKey("reactivatedAt"));
            assertThat(new LinkedHashMap<Object, Object>((Map<?, ?>) stored.paymentMethod.get("card"))).containsEntry("invalid", true);
        }
    }

    /**
     * Step 5: `CLUB.billing {invoiceSeriesPattern, nextNumber, resetYearly}` survives every full save of the club (the settings
     * and modules writers rebuild the record) and `club:apply`, which never exports it; the Stripe secrets reach neither
     * `club:export` nor `GET /club`.
     */
    @Test void WP_12_A_theClubBillingBlockSurvivesEveryWriterAndTheStripeSecretsNeverLeave() throws Exception {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(CLUB)), new Update().set("billing", new Document("invoiceSeriesPattern", "{YYYY}")
                .append("nextNumber", 913L).append("resetYearly", true)), Club.class);
        var club = clubs.findById(CLUB).orElseThrow();
        assertThat(club.billing()).isEqualTo(new Club.Billing("{YYYY}", 913L, true));
        // The settings/modules writers' path: the whole record through JSON and back, then a full save.
        var tree = (ObjectNode) mapper.valueToTree(club); tree.put("name", "Club Example renamed");
        clubs.save(mapper.convertValue(tree, Club.class));
        assertThat(clubs.findById(CLUB).orElseThrow().billing()).isEqualTo(new Club.Billing("{YYYY}", 913L, true));
        // club:apply merges a definition into the stored club: the numbering is operational state, never part of a definition.
        var stored = clubs.findById(CLUB).orElseThrow();
        var definition = definitions.export(stored);
        assertThat(definition.toString()).doesNotContain("nextNumber", "invoiceSeriesPattern", "PRIVATE_FIXTURE", "secretKeyEnc", "webhookSecretEnc");
        var merged = definitions.merge(definition, stored, stored.id(), clock.instant(), true);
        assertThat(merged.billing()).isEqualTo(stored.billing());
        // GET /club (D11) shows each provider's flags only.
        var body = mvc.perform(get("/api/v1/club").header("Host", HOST).with(jwt().jwt(j -> j.claim("clubId", CLUB)).authorities(() -> "ROLE_ADMIN")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("\"STRIPE\"").doesNotContain("PRIVATE_FIXTURE", "secretKeyEnc", "webhookSecretEnc", "nextNumber");
        assertThat(mapper.readTree(body).at("/paymentProviders/STRIPE").fieldNames()).toIterable().containsExactlyInAnyOrder("configured", "enabled");
        var stripe = com.agilityhub.core.platform.application.StripeProviderSettings.of(clubs.findById(CLUB).orElseThrow().paymentProviders());
        assertThat(stripe.enabled()).isTrue(); assertThat(mapper.writeValueAsString(stripe)).doesNotContain("PRIVATE_FIXTURE");
    }
}
