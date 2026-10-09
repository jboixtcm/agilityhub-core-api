package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.domain.BillingRunStatus;
import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PackBalanceState;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.IndexDefinition;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.index.IndexOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of the {@link BillingDocuments} repositories (S12 §3): the compare-and-set writes answer from the
 * matched/modified count, the reads return what Mongo found, the legacy unconditional number index is dropped once, and the
 * unique-when-present indexes are partial.
 */
class BillingDocumentsSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open(CLUB); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    // --- InvoiceRepository ------------------------------------------------------------------------------------------------

    @Test void T_12_13_onlyTheUnconditionalLegacyNumberIndexIsDropped() {
        var indexes = mock(IndexOperations.class);
        when(mongo.indexOps(Invoice.class)).thenReturn(indexes);
        // The E8-T01 guard without partial filter, next to an unrelated index without one either.
        var legacy = index("invoice_club_series_number", null);
        var other = index("invoice_club_period_status", null);
        when(indexes.getIndexInfo()).thenReturn(List.of(legacy, other));

        new BillingDocuments.InvoiceRepository(mongo).ensureIndexes();

        verify(indexes).dropIndex("invoice_club_series_number");
        verify(indexes, never()).dropIndex("invoice_club_period_status");
    }

    @Test void T_12_13_thePartialNumberGuardIsKept() {
        var indexes = mock(IndexOperations.class);
        when(mongo.indexOps(Invoice.class)).thenReturn(indexes);
        var partial = index("invoice_club_series_number", "{\"status\": {\"$in\": [\"PENDING\"]}}");
        when(indexes.getIndexInfo()).thenReturn(List.of(partial));

        new BillingDocuments.InvoiceRepository(mongo).ensureIndexes();

        verify(indexes, never()).dropIndex(anyString());
    }

    @Test void T_12_20_aMembersInvoicePageSkipsPageTimesSize() {
        new BillingDocuments.InvoiceRepository(mongo).ofMembers(List.of("member-1"), 2, 10);

        var query = ArgumentCaptor.forClass(Query.class);
        verify(mongo).find(query.capture(), eq(Invoice.class));
        assertThat(query.getValue().getSkip()).isEqualTo(20L);
        assertThat(query.getValue().getLimit()).isEqualTo(10);
    }

    @Test void T_12_14_aTransitionIsFalseWhenTheInvoiceMovedMeanwhile() {
        var invoices = new BillingDocuments.InvoiceRepository(mongo);
        var state = new BillingDocuments.InvoiceState(InvoiceStatus.PAID, null, NOW, null, null, null, null, NOW, "account-1");
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq("invoices"))).thenReturn(matched(0), matched(1));

        assertThat(invoices.transition("invoice-1", 3L, state)).isFalse();
        assertThat(invoices.transition("invoice-1", 3L, state)).isTrue();
    }

    // --- CollectionRepository ---------------------------------------------------------------------------------------------

    @Test void E11_T06_theProviderReferenceIndexIsUniqueOnlyWhenPresent() {
        var indexes = mock(IndexOperations.class);
        when(mongo.indexOps(Collection.class)).thenReturn(indexes);

        new BillingDocuments.CollectionRepository(mongo).ensureIndexes();

        var definitions = ArgumentCaptor.forClass(IndexDefinition.class);
        verify(indexes, atLeastOnce()).ensureIndex(definitions.capture());
        var providerRef = definitions.getAllValues().stream()
                .filter(definition -> "collection_club_provider_ref".equals(definition.getIndexOptions().getString("name"))).findFirst().orElseThrow();
        assertThat(providerRef.getIndexOptions().get("partialFilterExpression"))
                .isEqualTo(new Document("providerRef", new Document("$type", "string")));
    }

    @Test void T_12_13_theAttemptsOfTheInvoicesAreReturned() {
        var attempt = new Collection("collection-1", CLUB, "invoice-1", CollectionProvider.STRIPE, new Money(9000, "EUR"), CollectionStatus.SUCCEEDED,
                "pi_fake_1", null, 1, null, null, List.of(), NOW, NOW);
        when(mongo.find(any(Query.class), eq(Collection.class))).thenReturn(List.of(attempt));

        assertThat(new BillingDocuments.CollectionRepository(mongo).forInvoices(List.of("invoice-1"))).containsExactly(attempt);
    }

    // --- RemittanceRepository ---------------------------------------------------------------------------------------------

    @Test void T_12_13_aRemittanceRollbackIsFalseWhenItIsNoLongerGenerated() {
        var remittances = new BillingDocuments.RemittanceRepository(mongo);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq("remittances"))).thenReturn(matched(0), matched(1));

        assertThat(remittances.rollBack("remittance-1")).isFalse();
        assertThat(remittances.rollBack("remittance-1")).isTrue();
    }

    @Test void T_12_30_aSubmissionIsFalseWhenTheRemittanceIsNoLongerGenerated() {
        var remittances = new BillingDocuments.RemittanceRepository(mongo);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq("remittances"))).thenReturn(matched(0), matched(1));

        assertThat(remittances.submit("remittance-1", NOW, "account-1")).isFalse();
        assertThat(remittances.submit("remittance-1", NOW, "account-1")).isTrue();
    }

    // --- BillingRunRepository ---------------------------------------------------------------------------------------------

    @Test void T_12_15_aStoredCardRequestIsTheOneWithTheSameReference() {
        var row = new Document("_id", "run-1").append("cardRequests", List.of(
                new Document("reference", "ref-other").append("operations", List.of("operation-a")),
                new Document("reference", "ref-1").append("operations", List.of("operation-b"))));
        when(mongo.findOne(any(Query.class), eq(Document.class), eq("billing_runs"))).thenReturn(row);

        var request = new BillingDocuments.BillingRunRepository(mongo).cardRequest("run-1", "ref-1");

        assertThat(request.getString("reference")).isEqualTo("ref-1");
        assertThat(request.getList("operations", String.class)).containsExactly("operation-b");
    }

    @Test void T_12_15_chargingIsFalseWhenTheRunIsNeitherGeneratedNorCharging() {
        var runs = new BillingDocuments.BillingRunRepository(mongo);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq("billing_runs"))).thenReturn(matched(0), matched(1));

        assertThat(runs.charging("run-1")).isFalse();
        assertThat(runs.charging("run-1")).isTrue();
    }

    @Test void T_12_13_theMonthsLiveRunWinsOverItsRolledBackOne() {
        var live = run("run-live", BillingRunStatus.GENERATED);
        var rolledBack = run("run-rolled-back", BillingRunStatus.ROLLED_BACK);
        // The live read filters on status; the fallback read does not.
        when(mongo.findOne(any(Query.class), eq(BillingRun.class)))
                .thenAnswer(call -> ((Query) call.getArgument(0)).getQueryObject().containsKey("status") ? live : rolledBack);

        assertThat(new BillingDocuments.BillingRunRepository(mongo).latest("2026-10")).containsSame(live);
    }

    @Test void T_12_13_withoutALiveRunTheLatestRolledBackOneIsShown() {
        var rolledBack = run("run-rolled-back", BillingRunStatus.ROLLED_BACK);
        when(mongo.findOne(any(Query.class), eq(BillingRun.class)))
                .thenAnswer(call -> ((Query) call.getArgument(0)).getQueryObject().containsKey("status") ? null : rolledBack);

        assertThat(new BillingDocuments.BillingRunRepository(mongo).latest("2026-10")).containsSame(rolledBack);
    }

    @Test void T_12_13_aRunRollbackIsFalseWhenTheRunMovedMeanwhile() {
        var runs = new BillingDocuments.BillingRunRepository(mongo);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq("billing_runs"))).thenReturn(matched(0), matched(1));

        assertThat(runs.rollBack("run-1", 2L, NOW, "Wrong prices")).isFalse();
        assertThat(runs.rollBack("run-1", 2L, NOW, "Wrong prices")).isTrue();
    }

    // --- PackBalanceRepository --------------------------------------------------------------------------------------------

    @Test void T_12_16_thePackOfAnUpfrontPaymentIsFound() {
        var pack = new PackBalance("pack-1", CLUB, "member-1", "dog-1", "plan-1", "upfront-1", 10, 0, 10, "2026-10-01", "2027-02-28",
                PackBalanceState.ACTIVE, List.of(), null, null, null, Map.of(), 1L, NOW, "account-1");
        when(mongo.findOne(any(Query.class), eq(PackBalance.class))).thenReturn(pack);

        assertThat(new BillingDocuments.PackBalanceRepository(mongo).forPayment("upfront-1")).containsSame(pack);
    }

    // --- PendingChargeRepository ------------------------------------------------------------------------------------------

    @Test void T_12_09_billingIsFalseForAnAlreadyBilledOrVoidedCharge() {
        var charges = new BillingDocuments.PendingChargeRepository(mongo);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(PendingCharge.class))).thenReturn(matched(0), matched(1));

        assertThat(charges.bill("charge-1", "invoice-1")).isFalse();
        assertThat(charges.bill("charge-1", "invoice-1")).isTrue();
    }

    @Test void T_12_13_unbillReturnsHowManyChargesWentBack() {
        when(mongo.updateMulti(any(Query.class), any(UpdateDefinition.class), eq(PendingCharge.class)))
                .thenReturn(UpdateResult.acknowledged(3, 2L, null));

        assertThat(new BillingDocuments.PendingChargeRepository(mongo).unbill(List.of("invoice-1", "invoice-2"))).isEqualTo(2L);
    }

    @Test void T_12_08_voidingIsTrueBeforeBillingAndFalseOnceBilled() {
        var charges = new BillingDocuments.PendingChargeRepository(mongo);
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(PendingCharge.class))).thenReturn(matched(1), matched(0));

        assertThat(charges.voided("charge-1", NOW)).isTrue();
        assertThat(charges.voided("charge-1", NOW)).isFalse();
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static UpdateResult matched(long count) { return UpdateResult.acknowledged(count, count, null); }

    static IndexInfo index(String name, String partialFilter) {
        var info = mock(IndexInfo.class);
        when(info.getName()).thenReturn(name);
        when(info.getPartialFilterExpression()).thenReturn(partialFilter);
        return info;
    }

    static BillingRun run(String id, BillingRunStatus status) {
        return new BillingRun(id, CLUB, "2026-10", status, "simulation-1", List.of(), null, "2026-10-01", NOW, null, List.of(), List.of(),
                1L, "account-1", null, null, 1L, NOW);
    }
}
