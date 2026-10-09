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
    /**
     * R-12-14: the reason a rollback writes on the receipts it cancels (`CANCELLED{ROLLBACK}`) and on their `FAILED` collection.
     * It does not make a receipt rolled back: its run does ({@link InvoiceRepository#rolledBackRunIds()}).
     */
    public static final String ROLLBACK = "ROLLBACK";

    /**
     * R-12-14, §5 (E8-T07 step 4, ruling E89): the open club's rolled-back runs. A receipt is rolled back when its run is
     * `ROLLED_BACK` — every receipt of the run, one the admin had cancelled before included — never because of the words of its
     * `cancelReason`, which is the admin's free text. Its number went back to the counter and the next run reissues it, so it
     * never reaches the member (`/me/invoices`), D10, D6's «Tots» or the list's search: D6 shows it only under the `CANCELLED`
     * filter, marked as rolled back (round 2, ruling E87). A club has one run per month, read through `billing_run_club_status`.
     */
    public static List<String> rolledBackRunIds(MongoTemplate mongo, String clubId) {
        var query = org.springframework.data.mongodb.core.query.Query.query(Criteria.where("clubId").is(clubId).and("status").is("ROLLED_BACK"));
        query.fields().include("_id");
        return mongo.find(query, Document.class, "billing_runs").stream().map(run -> run.getString("_id")).toList();
    }
    /** The receipts that are not rolled back, given the club's rolled-back runs; null when nothing is (no condition to add). */
    public static Criteria notRolledBack(List<String> rolledBackRunIds) {
        return rolledBackRunIds.isEmpty() ? null : Criteria.where("runId").nin(rolledBackRunIds);
    }

    private static PartialIndexFilter present(String field) {
        return PartialIndexFilter.of(new Document(field, new Document("$type", "string")));
    }

    @Repository
    public static class InvoiceRepository extends TenantRepository<Invoice> {
        /**
         * E8-T07 step 5: the index the numbering reads walk, named in their hint. Left to itself the planner may cache
         * `invoice_club_run` for their shape — it wins the trial when the excluded rolled-back run holds every receipt (right after
         * a rollback) — and then scan and sort the club's receipts on every later reservation.
         */
        static final String SERIES_NUMBER_INDEX = "invoice_club_series_number_all";
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
            // E8-T07 step 5: the numbering reads (highestNumber, numberedFrom, seriesUsed) leave the cancelled receipts in and the
            // rolled-back ones out, which the partial index above cannot serve. This one covers every receipt (not unique; number
            // descending, so it is a different key pattern from the guard's).
            indexes.ensureIndex(new Index().on("clubId", ASC).on("series", ASC).on("number", DESC).named(SERIES_NUMBER_INDEX));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("period", ASC).on("status", ASC).named("invoice_club_period_status"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("issueDate", DESC).named("invoice_club_member_issue"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("runId", ASC).named("invoice_club_run"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("remittanceId", ASC).named("invoice_club_remittance"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("sourceIds.playoffReceiptId", ASC).unique()
                    .partial(present("sourceIds.playoffReceiptId")).named("invoice_club_playoff_source"));
        }
        /** R-12-10 (T-12-12): an issued invoice is never replaced — only its state fields move, through {@link #transition}. */
        @Override public Invoice replace(Invoice invoice) { throw new UnsupportedOperationException("R-12-10: an issued invoice is immutable"); }
        /** R-12-10: nothing is ever deleted; a wrong invoice is cancelled or corrected by a new one. */
        @Override public boolean deleteById(String id) { throw new UnsupportedOperationException("R-12-10: an issued invoice is never deleted"); }
        public List<Invoice> forRun(String runId) { return mongo.find(tenantQuery().addCriteria(Criteria.where("runId").is(runId)).with(Sort.by(ASC, "number")), Invoice.class); }
        public List<Invoice> forIds(java.util.Collection<String> ids) {
            return mongo.find(tenantQuery().addCriteria(Criteria.where("_id").in(List.copyOf(ids))), Invoice.class);
        }
        public void refunded(String id, com.agilityhub.core.shared.domain.Money amount, java.time.Instant at) {
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("refundedTotal", amount)
                    .set("updatedAt", at).inc("version", 1L), "invoices");
        }
        /** R-12-27: the invoices of {@code memberIds}, newest first; a rolled-back one never reaches the member. */
        public List<Invoice> ofMembers(java.util.Collection<String> memberIds, int page, int size) {
            return mongo.find(live(tenantQuery().addCriteria(Criteria.where("memberId").in(List.copyOf(memberIds))))
                    .with(Sort.by(DESC, "issueDate").and(Sort.by(DESC, "number"))).skip((long) page * size).limit(size), Invoice.class);
        }
        public long countOfMembers(java.util.Collection<String> memberIds) {
            return mongo.count(live(tenantQuery().addCriteria(Criteria.where("memberId").in(List.copyOf(memberIds)))), Invoice.class);
        }
        /** D6's chips of {@code period}: the invoices by status, the rolled-back ones aside (R-12-14). */
        public java.util.Map<String, Long> countsByStatus(String period) {
            var counts = new java.util.LinkedHashMap<String, Long>();
            for (var invoice : mongo.find(live(tenantQuery().addCriteria(Criteria.where("period").is(period))), Invoice.class)) {
                counts.merge(invoice.status().name(), 1L, Long::sum);
            }
            return counts;
        }
        /**
         * R-12-08: the highest number {@code series} has issued (0 before the first), the rolled-back receipts aside — so after a
         * rollback the floor ignores the run's whole block, a receipt of it the admin had cancelled included (E8-T07 step 2).
         */
        public long highestNumber(String series) {
            var query = live(tenantQuery().addCriteria(Criteria.where("series").is(series))).with(Sort.by(DESC, "number")).limit(1).withHint(SERIES_NUMBER_INDEX);
            query.fields().include("number");
            var top = mongo.findOne(query, Document.class, "invoices");
            return top == null || !(top.get("number") instanceof Number number) ? 0 : number.longValue();
        }
        /** Whether {@code series} has any receipt at all, a rolled-back one included. */
        public boolean seriesUsed(String series) { return mongo.exists(tenantQuery().addCriteria(Criteria.where("series").is(series)), Invoice.class); }
        /** R-12-14: whether {@code series} has a receipt numbered {@code number} or later, the rolled-back ones aside. */
        public boolean numberedFrom(String series, long number) {
            var query = live(tenantQuery().addCriteria(Criteria.where("series").is(series).and("number").gte(number))).limit(1).withHint(SERIES_NUMBER_INDEX);
            query.fields().include("_id");
            return mongo.findOne(query, Document.class, "invoices") != null;
        }
        /** The open club's rolled-back runs (see {@link BillingDocuments#rolledBackRunIds}). */
        public List<String> rolledBackRunIds() { return BillingDocuments.rolledBackRunIds(mongo, com.agilityhub.core.shared.application.TenantContext.require()); }
        /** R-12-14: whether {@code invoice} is rolled back — its run is `ROLLED_BACK` (E8-T07 step 4). */
        public boolean rolledBack(Invoice invoice) {
            return invoice.runId() != null && mongo.exists(tenantQuery().addCriteria(Criteria.where("_id").is(invoice.runId()).and("status").is("ROLLED_BACK")),
                    "billing_runs");
        }
        /** {@code query} without the rolled-back receipts. */
        private org.springframework.data.mongodb.core.query.Query live(org.springframework.data.mongodb.core.query.Query query) {
            var condition = notRolledBack(rolledBackRunIds());
            return condition == null ? query : query.addCriteria(condition);
        }
        /**
         * R-12-19 (round 2, ruling E87): the manual receipts waiting for the next run's remittance — `PENDING`, `SEPA_DD`,
         * `includeInNextRun`, in no remittance yet — in their numbers' order. Only a positive total can be debited (a pain.008
         * amount is at least 0.01; E8-T03, E8-T07 step 1, ruling E89): a zero or negative one stored with the flag before
         * `POST /invoices` refused it is never picked up, whatever its flag says, and waits for R-12-16. The simulation and the
         * run check each against its member (`InvoicingService`).
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
        public java.util.Optional<Collection> byProviderReference(String reference) {
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("providerRef").is(reference)), Collection.class));
        }
        public void submitted(String id, String providerRef) {
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("providerRef", providerRef), Collection.class);
        }
        public void resolve(String id, com.agilityhub.core.payments.domain.CollectionStatus status, String code, java.time.Instant at) {
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().set("status", status.name())
                    .set("failureCode", code).set("resolvedAt", at), Collection.class);
        }
        public void refund(String id, Collection.Refund refund, boolean full) {
            var update = new Update().push("refunds", refund);
            if (full) { update.set("status", "REFUNDED"); }
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), update, Collection.class);
        }
        public void reverseRefund(String id, String refundId) {
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("refunds.providerRef").is(refundId)),
                    new Update().pull("refunds", new Document("providerRef", refundId)).set("status", "SUCCEEDED"), Collection.class);
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
            var indexes = mongo.indexOps(BillingRun.class);
            indexes.ensureIndex(new Index().on("clubId", ASC).on("period", ASC).unique()
                    .partial(PartialIndexFilter.of(Criteria.where("status").in(LIVE_RUN_STATUSES))).named("billing_run_live_period"));
            // E8-T07: the rolled-back runs every receipt read leaves out (rolledBackRunIds).
            indexes.ensureIndex(new Index().on("clubId", ASC).on("status", ASC).named("billing_run_club_status"));
        }
        /** R-12-11: the month's live run (`GENERATED`, `CHARGING`, `COMPLETED`), if any. */
        public java.util.Optional<BillingRun> live(String period) {
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("period").is(period).and("status").in(LIVE_RUN_STATUSES)), BillingRun.class));
        }
        public org.bson.Document cardRequest(String id, String reference) {
            if (reference == null) { return null; }
            var row = mongo.findOne(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("cardRequests.reference").is(reference)), org.bson.Document.class, "billing_runs");
            return row == null ? null : row.getList("cardRequests", org.bson.Document.class).stream()
                    .filter(request -> reference.equals(request.getString("reference"))).findFirst().orElse(null);
        }
        public void cardRequest(String id, String reference, java.util.List<String> operations, java.util.List<org.bson.Document> skipped) {
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id)), new Update().push("cardRequests", new org.bson.Document("reference", reference)
                    .append("operations", operations).append("skipped", skipped)), "billing_runs");
        }
        public void resume(String id) {
            mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").in("COMPLETED", "CHARGING")),
                    new Update().set("status", "CHARGING").unset("finishedAt").inc("version", 1L), "billing_runs");
        }
        public boolean charging(String id) {
            return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").in("GENERATED", "CHARGING")),
                    new Update().set("status", "CHARGING").inc("version", 1L), "billing_runs").getMatchedCount() == 1;
        }
        public boolean progress(String id, int charged, int failed, boolean complete, java.time.Instant at) {
            var update = new Update().set("byProvider.stripe.charged", charged).set("byProvider.stripe.failed", failed).inc("version", 1L);
            if (complete) { update.set("status", "COMPLETED").set("finishedAt", at); }
            var previous = mongo.findAndModify(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("status").in("CHARGING", "COMPLETED")),
                    update, org.bson.Document.class, "billing_runs");
            return previous != null && "CHARGING".equals(previous.getString("status"));
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
        public List<PackBalance> of(String memberId, String dogId) {
            var query = tenantQuery();
            if (memberId != null) { query.addCriteria(Criteria.where("memberId").is(memberId)); }
            if (dogId != null) { query.addCriteria(Criteria.where("dogId").is(dogId)); }
            return mongo.find(query.with(Sort.by(ASC, "expiresOn", "_id")), PackBalance.class);
        }
        public java.util.Optional<PackBalance> forPayment(String id) {
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("upfrontPaymentId").is(id)), PackBalance.class));
        }
        public java.util.Optional<PackBalance> forBooking(String id) {
            return java.util.Optional.ofNullable(mongo.findOne(tenantQuery().addCriteria(Criteria.where("movements.bookingId").is(id)), PackBalance.class));
        }
        /** Replace only the version read by this transaction; movements are appended by the service. */
        public PackBalance save(PackBalance balance, Long expectedVersion) {
            var result = mongo.findAndReplace(tenantQuery(balance.clubId()).addCriteria(Criteria.where("_id").is(balance.id()).and("version").is(expectedVersion)),
                    balance, org.springframework.data.mongodb.core.FindAndReplaceOptions.options().returnNew());
            if (result == null) { throw new com.agilityhub.core.shared.domain.ApiException(com.agilityhub.core.shared.domain.ErrorCode.STALE_VERSION); }
            return result;
        }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            var indexes = mongo.indexOps(PackBalance.class);
            indexes.ensureIndex(new Index().on("clubId", ASC).on("dogId", ASC).on("state", ASC).on("expiresOn", ASC).named("pack_club_dog_state_expiry"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("state", ASC).named("pack_club_member_state"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("upfrontPaymentId", ASC).unique().partial(present("upfrontPaymentId")).named("pack_club_upfront_payment"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("sourceIds.playoffPackId", ASC).unique()
                    .partial(present("sourceIds.playoffPackId")).named("pack_club_playoff_source"));
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
        /** R-12-20: grows an unbilled CREDIT compensation from {@code expected} (false once billed, voided or changed meanwhile). */
        public boolean credit(String id, long expected, long amountMinor) {
            return mongo.updateFirst(tenantQuery().addCriteria(Criteria.where("_id").is(id).and("invoiceId").is(null).and("voidedAt").is(null)
                    .and("amount.amountMinor").is(expected)), new Update().set("amount.amountMinor", amountMinor), PendingCharge.class).getModifiedCount() == 1;
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
