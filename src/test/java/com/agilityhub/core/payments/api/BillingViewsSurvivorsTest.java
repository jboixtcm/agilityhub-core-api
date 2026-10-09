package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT survivors of {@link BillingViews} (S12 §3/§6, R-12-12, R-12-20; T-12-11, T-12-17): a collection publishes its
 * refunds, and a remittance publishes its creditor's name, identifier and BIC with the IBAN masked (an empty creditor when
 * the snapshot has none).
 */
class BillingViewsSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    static final Money AMOUNT = new Money(4500, "EUR");

    @Test void T_12_17_aCollectionPublishesEachOfItsRefunds() {
        var refund = new Collection.Refund(new Money(1500, "EUR"), "re_example", NOW, "Duplicate", "account-1");

        var view = BillingViews.collection(collection(List.of(refund)));

        assertThat(view.refunds()).containsExactly(new BillingContracts.CollectionRefund(new Money(1500, "EUR"), "re_example", NOW, "Duplicate", "account-1"));
        assertThat(BillingViews.collection(collection(null)).refunds()).isEmpty();
    }

    @Test void T_12_11_aRemittancePublishesItsCreditorWithTheIbanMasked() {
        var view = BillingViews.remittance(remittance(new Remittance.Creditor("Club Example", "ES00ZZZ00000000000", "ES0000000000000000004955", "EXMPESMMXXX")));

        assertThat(view.creditor()).isEqualTo(new BillingContracts.Creditor("Club Example", "ES00ZZZ00000000000", "···· ···· ···· ···· 4955", "EXMPESMMXXX"));
    }

    @Test void T_12_11_aRemittanceWithoutACreditorSnapshotPublishesAnEmptyOne() {
        assertThat(BillingViews.remittance(remittance(null)).creditor()).isEqualTo(new BillingContracts.Creditor("", "", "", null));
        assertThat(BillingViews.remittance(remittance(new Remittance.Creditor(null, null, null, null))).creditor())
                .isEqualTo(new BillingContracts.Creditor("", "", "", null));
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static Collection collection(List<Collection.Refund> refunds) {
        return new Collection("collection-1", "club-a", "invoice-1", CollectionProvider.STRIPE, AMOUNT, CollectionStatus.SUCCEEDED, "pi_example", null, 1,
                null, null, refunds, NOW, NOW, null, null, null, null, null);
    }

    static Remittance remittance(Remittance.Creditor creditor) {
        return new Remittance("remittance-1", "club-a", "run-1", "2026-10", "example-club-2026-10-1", NOW, "2026-10-05", creditor, List.of("collection-1"),
                1, AMOUNT, null, null, null, null, RemittanceStatus.GENERATED, null, null, 1L, NOW, null);
    }
}
