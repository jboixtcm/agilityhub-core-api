package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import static org.springframework.data.domain.Sort.Direction.ASC;
import static org.springframework.data.domain.Sort.Direction.DESC;

/**
 * The tenant repositories of the S12 collections (E8-T01, S12 §3, model §7). Each declares its indexes at start-up; E8-T02…T06
 * add the queries they need. The three guards E8's concurrency relies on: unique `{clubId, series, number}` on `invoices`
 * (R-08), the partial unique live `{clubId, period}` on `billing_runs` (R-12-11) and unique `{eventId}` on `stripe_events`
 * (R-12-21). Nothing is ever physically deleted, except a superseded simulation (only the last one per month is kept) and an
 * expired billing lock (TTL).
 */
public final class BillingDocuments {
    private BillingDocuments() { }
    /** The run statuses that hold the month (R-12-11 «one live run per club and period»). */
    public static final List<String> LIVE_RUN_STATUSES = List.of("GENERATED", "CHARGING", "COMPLETED");
    /** R-12-08/14: the invoice statuses whose number no other invoice of the series may hold (a cancelled one gives it up). */
    public static final List<String> NUMBERED_STATUSES = List.of("PENDING", "COLLECTING", "PAID", "FAILED");
    /** R-12-11: a lock its holder never released expires after ten minutes. */
    public static final Duration BILLING_LOCK_LEASE = Duration.ofMinutes(10);
    /** R-12-14: the `cancelReason` of a receipt cancelled by a rollback (`CANCELLED{ROLLBACK}`). */
    public static final String ROLLBACK = "ROLLBACK";

    /**
     * R-12-14 (round 2, ruling E87): a receipt cancelled by a rollback. Its number went back to the counter and the next run
     * reissues it, so it never reaches the member (`/me/invoices`), D6's «Tots» or the list's search: D6 shows it only under
     * the `CANCELLED` filter, marked as rolled back.
     */
    public static Document rolledBack() { return new Document("status", "CANCELLED").append("cancelReason", ROLLBACK); }
    /** Every receipt except the rolled-back ones ({@link #rolledBack()}). */
    public static Criteria notRolledBack() { return new Criteria().norOperator(Criteria.where("status").is("CANCELLED").and("cancelReason").is(ROLLBACK)); }

    private static PartialIndexFilter present(String field) {
        return PartialIndexFilter.of(new Document(field, new Document("$type", "string")));
    }

    @Repository
    public static class InvoiceRepository extends TenantRepository<Invoice> {
        public InvoiceRepository(MongoTemplate mongo) { super(mongo, Invoice.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            var indexes = mongo.indexOps(Invoice.class);
            // E8-T02 (R-12-14): a rollback gives its block of numbers back and the next generation reuses them, while the
            // cancelled invoices stay (nothing is deleted). The number guard covers the invoices that are not cancelled; the
            // unconditional index E8-T01 created is replaced once.
            for (var info : indexes.getIndexInfo()) {
                if (info.getName().equals("invoice_club_series_number") && info.getPartialFilterExpression() == null) { indexes.dropIndex(info.getName()); }
            }
            indexes.ensureIndex(new Index().on("clubId", ASC).on("series", ASC).on("number", ASC).unique()
                    .partial(PartialIndexFilter.of(Criteria.where("status").in(NUMBERED_STATUSES))).named("invoice_club_series_number"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("period", ASC).on("status", ASC).named("invoice_club_period_status"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("issueDate", DESC).named("invoice_club_member_issue"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("runId", ASC).named("invoice_club_run"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("remittanceId", ASC).named("invoice_club_remittance"));
        }
        /** R-12-10 (T-12-12): an issued invoice is never replaced — only its state fields move, through {@link #transition}. */
        @Override public Invoice replace(Invoice invoice) { throw new UnsupportedOperationException("R-12-10: an issued invoice is immutable"); }
        /** R-12-10: nothing is ever deleted; a wrong invoice is cancelled or corrected by a new one. */
        @Override public boolean deleteById(String id) { throw new UnsupportedOperationException("R-12-10: an issued invoice is never deleted"); }
        public List<Invoice> forRun(String runId) { return mongo.find(tenantQuery().addCriteria(Criteria.where("runId").is(runId)).with(Sort.by(ASC, "number")), Invoice.class); }
        public List<Invoice> forIds(java.util.Collection<String> ids) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(List.copyOf(ids))), Invoice.class);
        }
        /** R-12-27: the invoices of {@code memberIds}, newest first; a rolled-back one never reaches the member. */
        public List<Invoice> ofMembers(java.util.Collection<String> memberIds, int page, int size) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").in(List.copyOf(memberIds))).addCriteria(notRolledBack())
                    .with(Sort.by(DESC, "issueDate").and(Sort.by(DESC, "number"))).skip((long) page * size).limit(size), Invoice.class);
        }
        public long countOfMembers(java.util.Collection<String> memberIds) {
            return mongo.count(tenantQuery().addCriteria(Criteria.where("memberId").in(List.copyOf(memberIds))).addCriteria(notRolledBack()), Invoice.class);
        }
        /** D6's chips of {@code period}: the invoices by status, the rolled-back ones aside (R-12-14). */
        public java.util.Map<String, Long> countsByStatus(String period) {
            var counts = new java.util.LinkedHashMap<String, Long>();
            for (var invoice : mongo.find(tenantQuery().addCriteria(Criteria.where("period").is(period)).addCriteria(notRolledBack()), Invoice.class)) {
                counts.merge(invoice.status().name(), 1L, Long::sum);
            }
            return counts;
        }
        /** R-12-08: the highest number {@code series} has issued (0 before the first), the rolled-back receipts aside. */
        public long highestNumber(String series) {
            var query = tenantQuery().addCriteria(Criteria.where("series").is(series)).addCriteria(notRolledBack()).with(Sort.by(DESC, "number")).limit(1);
            query.fields().include("number");
            var top = mongo.findOne(query, Document.class, "invoices");
            return top == null || !(top.get("number") instanceof Number number) ? 0 : number.longValue();
        }
        /** Whether {@code series} has any receipt at all, a rolled-back one included. */
        public boolean seriesUsed(String series) { return mongo.exists(tenantQuery().addCriteria(Criteria.where("series").is(series)), Invoice.class); }
        /** R-12-14: whether {@code series} has a receipt numbered {@code number} or later, the rolled-back ones aside. */
        public boolean numberedFrom(String series, long number) {
            return mongo.exists(tenantQuery().addCriteria(Criteria.where("series").is(series).and("number").gte(number)).addCriteria(notRolledBack()), Invoice.class);
        }
        /**
         * R-12-19 (round 2, ruling E87): the manual receipts the next run puts into its remittance — `PENDING`, `SEPA_DD`,
         * `includeInNextRun`, in no remittance yet — in their numbers' order. E8-T03: only a positive total can be debited (a
         * pain.008 amount is at least 0.01); a zero or negative adjustment waits for R-12-16 instead of failing every run.
         */
        public List<Invoice> forNextRun() {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("kind").is("MANUAL").and("status").is("PENDING").and("includeInNextRun").is(true)
                    .and("paymentMethod.type").is("SEPA_DD").and("remittanceId").is(null).and("total.amountMinor").gt(0)).with(Sort.by(ASC, "number")), Invoice.class);
        }
        /** The manual receipts a run put into the remittance {@code remittanceId} (R-12-19): no run of their own. */
        public List<Invoice> includedIn(String remittanceId) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("remittanceId").is(remittanceId).and("runId").is(null)).with(Sort.by(ASC, "number")), Invoice.class);
        }
        /**
         * The state fields of §5's transitions, and only them (R-12-10): lines, amounts, member, series and number never change.
         * A compare-and-set on {@code expectedVersion}; false when the invoice moved meanwhile.
         */
        public boolean transition(String id, long expectedVersion, InvoiceState state) {
            var query = tenantQuery().addCriteria(Criteria.where("_id").is(id).and("version").is(expectedVersion));
            var update = new Update().set("status", state.status().name()).set("remittanceId", state.remittanceId()).set("paidAt", state.paidAt())
                    .set("failedAt", state.failedAt()).set("failureReason", state.failureReason()).set("cancelledAt", state.cancelledAt())
                    .set("cancelReason", state.cancelReason()).set("updatedAt", state.at()).set("updatedByAccountId", state.byAccountId()).inc("version", 1L);
            return mongo.updateFirst(query, update, "invoices").getMatchedCount() == 1;
        }
    }
    /** The state of an invoice after one of §5's transitions (R-12-10: nothing else of an issued invoice changes). */
    public record InvoiceState(com.agilityhub.core.payments.domain.InvoiceStatus status, String remittanceId, java.time.Instant paidAt,
            java.time.Instant failedAt, String failureReason, java.time.Instant cancelledAt, String cancelReason, java.time.Instant at, String byAccountId) {
        public static InvoiceState of(Invoice invoice, java.time.Instant at, String byAccountId) {
            return new InvoiceState(invoice.status(), invoice.remittanceId(), invoice.paidAt(), invoice.failedAt(), invoice.failureReason(),
                    invoice.cancelledAt(), invoice.cancelReason(), at, byAccountId);
        }
        public InvoiceState status(com.agilityhub.core.payments.domain.InvoiceStatus next) {
            return new InvoiceState(next, remittanceId, paidAt, failedAt, failureReason, cancelledAt, cancelReason, at, byAccountId);
        }
        /** The remittance a manual receipt joins (R-12-19) or leaves (null, R-12-14). */
        public InvoiceState remittance(String id) { return new InvoiceState(status, id, paidAt, failedAt, failureReason, cancelledAt, cancelReason, at, byAccountId); }
        public InvoiceState paid(java.time.Instant when) { return new InvoiceState(status, remittanceId, when, failedAt, failureReason, cancelledAt, cancelReason, at, byAccountId); }
        public InvoiceState failed(java.time.Instant when, String reason) { return new InvoiceState(status, remittanceId, paidAt, when, reason, cancelledAt, cancelReason, at, byAccountId); }
        public InvoiceState cancelled(java.time.Instant when, String reason) { return new InvoiceState(status, remittanceId, paidAt, failedAt, failureReason, when, reason, at, byAccountId); }
    }

    @Repository
    public static class CollectionRepository extends TenantRepository<Collection> {
        public CollectionRepository(MongoTemplate mongo) { super(mongo, Collection.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            var indexes = mongo.indexOps(Collection.class);
            indexes.ensureIndex(new Index().on("clubId", ASC).on("invoiceId", ASC).on("attempt", ASC).named("collection_club_invoice_attempt"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("provider", ASC).on("status", ASC).named("collection_club_provider_status"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("remittanceId", ASC).named("collection_club_remittance"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("providerRef", ASC).unique().partial(present("providerRef")).named("collection_club_provider_ref"));
        }
        /** The invoice's attempts, oldest first (the detail's `collections[]`). */
        public List<Collection> forInvoice(String invoiceId) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("invoiceId").is(invoiceId))
                    .with(org.springframework.data.domain.Sort.by(ASC, "attempt", "createdAt")), Collection.class);
        }
        public List<Collection> forInvoices(java.util.Collection<String> invoiceIds) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("invoiceId").in(List.copyOf(invoiceIds)))
                    .with(org.springframework.data.domain.Sort.by(ASC, "attempt", "createdAt")), Collection.class);
        }
        /** R-12-12 (E8-T03): the `SEPA_XML` attempts under {@code mandateRefs}, to tell a mandate already collected (`RCUR`) from a new one (`FRST`). */
        public List<Collection> sepaAttempts(java.util.Collection<String> mandateRefs) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("provider").is("SEPA_XML").and("mandateRef").in(List.copyOf(mandateRefs))), Collection.class);
        }
        /** R-12-10: a collection attempt is append-only — a new outcome is a new document. */
        @Override public Collection replace(Collection collection) { throw new UnsupportedOperationException("R-12-10: a collection is append-only"); }
        @Override public boolean deleteById(String id) { throw new UnsupportedOperationException("R-12-10: a collection is append-only"); }
    }

    @Repository
    public static class RemittanceRepository extends TenantRepository<Remittance> {
        public RemittanceRepository(MongoTemplate mongo) { super(mongo, Remittance.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            var indexes = mongo.indexOps(Remittance.class);
            indexes.ensureIndex(new Index().on("clubId", ASC).on("period", ASC).on("status", ASC).named("remittance_club_period_status"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("messageId", ASC).unique().named("remittance_club_message"));
        }
        public List<Remittance> forPeriod(String period) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("period").is(period)).with(Sort.by(DESC, "creationAt")), Remittance.class);
        }
        public java.util.Optional<Remittance> forRun(String runId) {
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("runId").is(runId)), Remittance.class));
        }
        /** R-12-14: `GENERATED → ROLLED_BACK`, the file kept; false when it is no longer `GENERATED`. */
        public boolean rollBack(String id) {
            return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").is("GENERATED")),
                    new Update().set("status", "ROLLED_BACK").inc("version", 1L), "remittances").getMatchedCount() == 1;
        }
        /** R-12-15 (E8-T03): `GENERATED → SUBMITTED` with when and by whom; false when it is no longer `GENERATED`. */
        public boolean submit(String id, java.time.Instant submittedAt, String accountId) {
            return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").is("GENERATED")),
                    new Update().set("status", "SUBMITTED").set("submittedAt", submittedAt).set("submittedByAccountId", accountId).inc("version", 1L),
                    "remittances").getMatchedCount() == 1;
        }
        /** R-12-12 (E8-T03): which of {@code ids} were marked as sent to the bank (their debits count as submitted). */
        public java.util.Set<String> submittedAmong(java.util.Collection<String> ids) {
            var query = tenantQuery().addCriteria(Criteria.where("_id").in(List.copyOf(ids)).and("status").is("SUBMITTED"));
            query.fields().include("_id");
            var found = new java.util.HashSet<String>();
            mongo.find(query, Document.class, "remittances").forEach(document -> found.add(document.getString("_id")));
            return found;
        }
        @Override public Remittance replace(Remittance remittance) { throw new UnsupportedOperationException("R-12-10: a remittance is append-only"); }
        @Override public boolean deleteById(String id) { throw new UnsupportedOperationException("R-12-10: a remittance is append-only"); }
    }

    @Repository
    public static class BillingRunRepository extends TenantRepository<BillingRun> {
        public BillingRunRepository(MongoTemplate mongo) { super(mongo, BillingRun.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            mongo.indexOps(BillingRun.class).ensureIndex(new Index().on("clubId", ASC).on("period", ASC).unique()
                    .partial(PartialIndexFilter.of(Criteria.where("status").in(LIVE_RUN_STATUSES))).named("billing_run_live_period"));
        }
        /** R-12-11: the month's live run (`GENERATED`, `CHARGING`, `COMPLETED`), if any. */
        public java.util.Optional<BillingRun> live(String period) {
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("period").is(period).and("status").in(LIVE_RUN_STATUSES)), BillingRun.class));
        }
        /** D6: the month's live run, else its latest rolled-back one. */
        public java.util.Optional<BillingRun> latest(String period) {
            var live = live(period);
            if (live.isPresent()) { return live; }
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("period").is(period)).with(Sort.by(DESC, "startedAt")).limit(1), BillingRun.class));
        }
        /** R-12-14: `GENERATED/CHARGING → ROLLED_BACK` with its reason; false when the run moved meanwhile. */
        public boolean rollBack(String id, long expectedVersion, java.time.Instant at, String reason) {
            return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("version").is(expectedVersion)
                            .and("status").in("GENERATED", "CHARGING")),
                    new Update().set("status", "ROLLED_BACK").set("rolledBackAt", at).set("rollbackReason", reason).inc("version", 1L), "billing_runs").getMatchedCount() == 1;
        }
    }

    @Repository
    public static class BillingSimulationRepository extends TenantRepository<BillingSimulation> {
        public BillingSimulationRepository(MongoTemplate mongo) { super(mongo, BillingSimulation.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            mongo.indexOps(BillingSimulation.class).ensureIndex(new Index().on("clubId", ASC).on("period", ASC).unique().named("billing_simulation_club_period"));
        }
        public java.util.Optional<BillingSimulation> forPeriod(String period) {
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("period").is(period)), BillingSimulation.class));
        }
        /** R-12-07: the month keeps only its last simulation — the new one replaces it (unique `{clubId, period}`). */
        public BillingSimulation store(BillingSimulation simulation) {
            mongo.remove(tenantQuery(simulation.clubId()).addCriteria(Criteria.where("period").is(simulation.period())), BillingSimulation.class);
            return insert(simulation);
        }
    }

    @Repository
    public static class PackBalanceRepository extends TenantRepository<PackBalance> {
        public PackBalanceRepository(MongoTemplate mongo) { super(mongo, PackBalance.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            var indexes = mongo.indexOps(PackBalance.class);
            indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("state", ASC).on("expiresOn", ASC).named("pack_club_dog_state_expiry"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("state", ASC).named("pack_club_member_state"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("upfrontPaymentId", ASC).unique().partial(present("upfrontPaymentId")).named("pack_club_upfront_payment"));
        }
    }

    @Repository
    public static class PendingChargeRepository extends TenantRepository<PendingCharge> {
        public PendingChargeRepository(MongoTemplate mongo) { super(mongo, PendingCharge.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            var indexes = mongo.indexOps(PendingCharge.class);
            indexes.ensureIndex(new Index().on("clubId", ASC).on("bookingId", ASC).unique().named("pending_charge_club_booking"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("invoiceId", ASC).named("pending_charge_club_member_invoice"));
        }
        public java.util.Optional<PendingCharge> forBooking(String bookingId) {
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("bookingId").is(bookingId)), PendingCharge.class));
        }
        /** R-12-25: the charges the next run bills — not billed, not voided — of {@code memberIds}. */
        public List<PendingCharge> unbilled(java.util.Collection<String> memberIds) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").in(List.copyOf(memberIds)).and("invoiceId").is(null).and("voidedAt").is(null))
                    .with(Sort.by(ASC, "createdAt")), PendingCharge.class);
        }
        public List<PendingCharge> forMember(String memberId) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("memberId").is(memberId)).with(Sort.by(DESC, "createdAt")), PendingCharge.class);
        }
        /** R-12-11: the run stamps its charges with their invoice; only unbilled ones (false = already billed or voided). */
        public boolean bill(String id, String invoiceId) {
            return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("invoiceId").is(null).and("voidedAt").is(null)),
                    new Update().set("invoiceId", invoiceId), PendingCharge.class).getMatchedCount() == 1;
        }
        /** R-12-14: the rollback gives the charges of the cancelled invoices back to the next run. */
        public long unbill(java.util.Collection<String> invoiceIds) {
            return mongo.updateMulti(tenantQuery().addCriteria(Criteria.where("invoiceId").in(List.copyOf(invoiceIds))),
                    new Update().set("invoiceId", null), PendingCharge.class).getModifiedCount();
        }
        /** S10 R-10-07: voided (`at`) or reinstated (null) before billing; false once billed. */
        public boolean voided(String id, java.time.Instant at) {
            return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("invoiceId").is(null)),
                    new Update().set("voidedAt", at), PendingCharge.class).getMatchedCount() == 1;
        }
    }

    @Repository
    public static class StripeEventRepository extends TenantRepository<StripeEvent> {
        public StripeEventRepository(MongoTemplate mongo) { super(mongo, StripeEvent.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            var indexes = mongo.indexOps(StripeEvent.class);
            indexes.ensureIndex(new Index().on("eventId", ASC).unique().named("stripe_event_id"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("receivedAt", ASC).named("stripe_event_club_received"));
        }
    }

    @Repository
    public static class BillingLockRepository extends TenantRepository<BillingLock> {
        public BillingLockRepository(MongoTemplate mongo) { super(mongo, BillingLock.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            mongo.indexOps(BillingLock.class).ensureIndex(new Index().on("expiresAt", ASC).expire(Duration.ZERO).named("billing_lock_ttl"));
        }
        /**
         * R-12-11: takes the club's billing lock for {@code holder} for {@link #BILLING_LOCK_LEASE}, outside any transaction so that
         * every other request sees it at once; a lease that ran out (and the TTL monitor did not sweep yet) is taken over. False
         * while another holder has it (`409 BILLING_BUSY`).
         */
        public boolean acquire(String holder, java.time.Instant now) {
            String club = com.agilityhub.core.shared.application.TenantContext.require(); String id = BillingLock.idFor(club);
            try { mongo.insert(new BillingLock(id, club, holder, now, now.plus(BILLING_LOCK_LEASE))); return true; }
            catch (org.springframework.dao.DuplicateKeyException held) {
                return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("expiresAt").lte(now)),
                        new Update().set("holder", holder).set("acquiredAt", now).set("expiresAt", now.plus(BILLING_LOCK_LEASE)), BillingLock.class).getMatchedCount() == 1;
            }
        }
        /** Releases the lock if {@code holder} still has it. */
        public void release(String holder) {
            mongo.remove(tenantQuery().addCriteria(Criteria.where("_id").is(BillingLock.idFor(com.agilityhub.core.shared.application.TenantContext.require()))
                    .and("holder").is(holder)), BillingLock.class);
        }
        /** Whether a holder has the club's lock now (a manual invoice waits for the run, R-12-08). */
        public boolean held(java.time.Instant now) {
            return mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(BillingLock.idFor(com.agilityhub.core.shared.application.TenantContext.require()))
                    .and("expiresAt").gt(now)), BillingLock.class);
        }
    }
}
