package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.application.PriceResolver;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.support.AbstractIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoTemplate;

import static com.mongodb.client.model.Filters.eq;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * INC-68 (E11-T08): a price's validity dates are stored as `YYYY-MM-DD` strings whichever way the price is written (a whole
 * insert or a partial update), so the price chosen for a billing date, the order of a plan's prices and the unique start
 * index never compare a BSON date with a string. Prices stored as dates before the fix are read with their own day and
 * converted at startup.
 */
@ExtendWith(OutputCaptureExtension.class)
class PriceDatesIT extends AbstractIntegrationTest {
    private static final String CLUB = "club-inc-68";
    private static final String PLAN = "plan-inc-68";
    private static final Instant WRITTEN = Instant.parse("2026-01-01T09:00:00Z");

    @Autowired private PriceRepository prices;
    @Autowired private PriceResolver resolver;
    @Autowired private MongoTemplate mongo;

    @AfterEach void dropPrices() {
        mongo.getCollection("prices").deleteMany(eq("clubId", CLUB));
        mongo.getCollection("members").deleteMany(eq("clubId", CLUB));
        mongo.getCollection("pending_charges").deleteMany(eq("clubId", CLUB));
    }

    @Test void INC_68_aPriceWrittenWholeOrPatchedIsStoredAsADayStringAndChosenForTheBillingDate() {
        try (var tenant = TenantContext.open(CLUB)) {
            var january = prices.insert(price("inc68-jan", "2026-01-01", null, 0));
            prices.update(price("inc68-jan", "2026-01-01", "2026-03-31", 1), january.version());
            prices.insert(price("inc68-apr", "2026-04-01", null, 0));

            assertThat(raw("inc68-jan")).containsEntry("validFrom", "2026-01-01").containsEntry("validTo", "2026-03-31");
            assertThat(raw("inc68-apr")).containsEntry("validFrom", "2026-04-01").doesNotContainKey("validTo");

            assertThat(resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2025-12-31"))).isEmpty();
            assertThat(resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2026-03-31"))).get().extracting(Price::id).isEqualTo("inc68-jan");
            assertThat(resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2026-04-01"))).get().extracting(Price::id).isEqualTo("inc68-apr");
            assertThat(prices.forPlan(PLAN)).extracting(Price::id).containsExactly("inc68-jan", "inc68-apr");
        }
    }

    @Test void INC_68_aSecondPriceStartingOnTheSameDayIsRefusedWhicheverWayTheFirstWasWritten() {
        try (var tenant = TenantContext.open(CLUB)) {
            var january = prices.insert(price("inc68-jan", "2026-01-01", null, 0));
            prices.update(price("inc68-jan", "2026-01-01", "2026-03-31", 1), january.version());

            // Before the fix the patched start was a string and the inserted one a date: the unique index saw two starts.
            assertThatThrownBy(() -> prices.insert(price("inc68-twin", "2026-01-01", null, 0))).isInstanceOf(DuplicateKeyException.class);
        }
    }

    @Test void INC_68_pricesStoredAsDatesAreReadWithTheirDayAndConvertedAtStartup() {
        // As an insert stored them before the fix: the JVM zone's midnight of the day (Spring Data's LocalDate -> Date).
        mongo.getCollection("prices").insertOne(legacy("inc68-legacy", "2026-01-01", "2026-03-31"));
        mongo.getCollection("prices").insertOne(legacy("inc68-legacy-open", "2026-04-01", null));

        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2026-03-31"))).get().extracting(Price::id).isEqualTo("inc68-legacy");
            assertThat(resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2026-04-01"))).get().extracting(Price::id).isEqualTo("inc68-legacy-open");
        }

        assertThat(prices.normaliseValidityDates()).isGreaterThanOrEqualTo(2);
        assertThat(raw("inc68-legacy")).containsEntry("validFrom", "2026-01-01").containsEntry("validTo", "2026-03-31");
        assertThat(raw("inc68-legacy-open")).containsEntry("validFrom", "2026-04-01").containsEntry("validTo", null);
        assertThat(prices.normaliseValidityDates()).isZero();

        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(prices.forPlan(PLAN)).extracting(Price::id).containsExactly("inc68-legacy", "inc68-legacy-open");
            assertThat(resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2026-04-01"))).get().extracting(Price::id).isEqualTo("inc68-legacy-open");
        }
    }

    // Before the fix the unique `price_start` index saw a date and a string as two starts of the same day: twins could exist.

    @Test void INC_68_round2_aLegacyTwinIdenticalToThePriceKeptIsRemovedAtStartup(CapturedOutput output) throws Exception {
        mongo.getCollection("prices").insertOne(legacy("inc68-legacy-twin", "2026-01-01", null, 4500));
        try (var tenant = TenantContext.open(CLUB)) { prices.insert(price("inc68-string", "2026-01-01", null, 0)); }

        startup();
        assertThat(raw("inc68-legacy-twin")).isNull();
        assertThat(raw("inc68-string")).containsEntry("validFrom", "2026-01-01");
        assertThat(output).contains("Price start twins removed count=1 ids=[inc68-legacy-twin]").doesNotContain("PriceStartTwins");
        // INC-74 (E11-T13): a removal at startup is logged at WARN, with the same text.
        assertThat(output.getOut()).containsPattern("WARN +\\[[^]]*] \\S*PriceRepository .*: Price start twins removed count=1 ids=\\[inc68-legacy-twin]");
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2026-02-01"))).get().extracting(Price::id).isEqualTo("inc68-string");
        }
    }

    /**
     * INC-74 (E11-T13): the references that name a price are `members.priceId`, `invoices.lines.priceId` and
     * `pending_charges.priceId` (`Collection` and `PackBalance` carry none). An identical twin that only a pending charge
     * names is kept and reported.
     */
    @Test void INC_74_anIdenticalTwinThatOnlyAPendingChargeNamesIsKeptAndReported(CapturedOutput output) throws Exception {
        mongo.getCollection("prices").insertOne(legacy("inc68-legacy-twin", "2026-01-01", null, 4500));
        try (var tenant = TenantContext.open(CLUB)) { prices.insert(price("inc68-string", "2026-01-01", null, 0)); }
        mongo.getCollection("pending_charges").insertOne(new Document("_id", "inc74-charge").append("clubId", CLUB).append("memberId", "inc74-member")
                .append("priceId", "inc68-legacy-twin").append("amount", new Document("amountMinor", 4500L).append("currency", "EUR")));

        startup();
        assertThat(raw("inc68-legacy-twin").get("validFrom")).isInstanceOf(Date.class);
        assertThat(output).contains("PriceStartTwins count=1 ids=[inc68-legacy-twin]").doesNotContain("Price start twins removed");
    }

    @Test void INC_68_round2_anIdenticalTwinThatADocumentNamesIsKeptAndReported(CapturedOutput output) throws Exception {
        mongo.getCollection("prices").insertOne(legacy("inc68-legacy-twin", "2026-01-01", null, 4500));
        try (var tenant = TenantContext.open(CLUB)) { prices.insert(price("inc68-string", "2026-01-01", null, 0)); }
        // A member's price (`member.priceId`) names the legacy twin: removing it would leave that reference dangling.
        mongo.getCollection("members").insertOne(new Document("_id", "inc68-member").append("clubId", CLUB).append("priceId", "inc68-legacy-twin"));

        startup();
        assertThat(raw("inc68-legacy-twin").get("validFrom")).isInstanceOf(Date.class);
        assertThat(output).contains("PriceStartTwins count=1 ids=[inc68-legacy-twin]");
    }

    @Test void INC_68_round2_aTwinThatDiffersIsReportedAndBillingNeverChoosesBetweenThem(CapturedOutput output) throws Exception {
        mongo.getCollection("prices").insertOne(legacy("inc68-legacy-twin", "2026-01-01", null, 5000));
        try (var tenant = TenantContext.open(CLUB)) { prices.insert(price("inc68-string", "2026-01-01", null, 0)); }

        startup();
        assertThat(raw("inc68-legacy-twin").get("validFrom")).isInstanceOf(Date.class);
        assertThat(raw("inc68-legacy-twin").get("amount", Document.class).get("amountMinor")).isEqualTo(5000L);
        assertThat(output).contains("PriceStartTwins count=1 ids=[inc68-legacy-twin]");
        try (var tenant = TenantContext.open(CLUB)) {
            assertThatThrownBy(() -> resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2026-02-01")))
                    .isInstanceOfSatisfying(ApiException.class, refused -> {
                        assertThat(refused.code()).isEqualTo(ErrorCode.PRICE_OVERLAP);
                        assertThat(refused.details()).containsEntry("planId", PLAN).containsEntry("concept", "MONTHLY_FEE");
                        assertThat(java.util.List.of(refused.details().get("priceId"), refused.details().get("conflictingPriceId")))
                                .containsExactlyInAnyOrder("inc68-string", "inc68-legacy-twin");
                    });
            // Before the twins start there is no price, and no ambiguity.
            assertThat(resolver.current(PLAN, PriceConcept.MONTHLY_FEE, LocalDate.parse("2025-12-31"))).isEmpty();
        }
    }

    private void startup() throws Exception { new CatalogPersistence().priceValidityDays(prices).run(null); }

    private static Price price(String id, String from, String to, long version) {
        return new Price(id, CLUB, PLAN, PriceConcept.MONTHLY_FEE, new Money(4500, "EUR"), new BigDecimal("21"), LocalDate.parse(from),
                to == null ? null : LocalDate.parse(to), version, WRITTEN, WRITTEN, "account-inc-68", "account-inc-68");
    }

    private static Document legacy(String id, String from, String to) { return legacy(id, from, to, 4500); }

    private static Document legacy(String id, String from, String to, long amountMinor) {
        return new Document("_id", id).append("clubId", CLUB).append("planId", PLAN).append("concept", "MONTHLY_FEE")
                .append("amount", new Document("amountMinor", amountMinor).append("currency", "EUR")).append("taxPercent", "21")
                .append("validFrom", midnight(from)).append("validTo", to == null ? null : midnight(to)).append("version", 0L)
                .append("createdAt", Date.from(WRITTEN)).append("updatedAt", Date.from(WRITTEN))
                .append("createdByAccountId", "account-inc-68").append("updatedByAccountId", "account-inc-68")
                .append("_class", Price.class.getName());
    }

    private static Date midnight(String day) { return Date.from(LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant()); }

    private Document raw(String id) { return mongo.getCollection("prices").find(eq("_id", id)).first(); }
}
