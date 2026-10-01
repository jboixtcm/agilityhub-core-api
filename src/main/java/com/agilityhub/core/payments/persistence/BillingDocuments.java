package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.persistence.TenantRepository;
import java.time.Duration;
import java.util.List;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.PartialIndexFilter;
import org.springframework.data.mongodb.core.query.Criteria;
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
    /** R-12-11: a lock its holder never released expires after ten minutes. */
    public static final Duration BILLING_LOCK_LEASE = Duration.ofMinutes(10);

    private static PartialIndexFilter present(String field) {
        return PartialIndexFilter.of(new Document(field, new Document("$type", "string")));
    }

    @Repository
    public static class InvoiceRepository extends TenantRepository<Invoice> {
        public InvoiceRepository(MongoTemplate mongo) { super(mongo, Invoice.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            var indexes = mongo.indexOps(Invoice.class);
            indexes.ensureIndex(new Index().on("clubId", ASC).on("series", ASC).on("number", ASC).unique().named("invoice_club_series_number"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("period", ASC).on("status", ASC).named("invoice_club_period_status"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("memberId", ASC).on("issueDate", DESC).named("invoice_club_member_issue"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("runId", ASC).named("invoice_club_run"));
            indexes.ensureIndex(new Index().on("clubId", ASC).on("remittanceId", ASC).named("invoice_club_remittance"));
        }
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
    }

    @Repository
    public static class BillingRunRepository extends TenantRepository<BillingRun> {
        public BillingRunRepository(MongoTemplate mongo) { super(mongo, BillingRun.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            mongo.indexOps(BillingRun.class).ensureIndex(new Index().on("clubId", ASC).on("period", ASC).unique()
                    .partial(PartialIndexFilter.of(Criteria.where("status").in(LIVE_RUN_STATUSES))).named("billing_run_live_period"));
        }
    }

    @Repository
    public static class BillingSimulationRepository extends TenantRepository<BillingSimulation> {
        public BillingSimulationRepository(MongoTemplate mongo) { super(mongo, BillingSimulation.class); }
        @jakarta.annotation.PostConstruct
        public void ensureIndexes() {
            mongo.indexOps(BillingSimulation.class).ensureIndex(new Index().on("clubId", ASC).on("period", ASC).unique().named("billing_simulation_club_period"));
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
    }
}
