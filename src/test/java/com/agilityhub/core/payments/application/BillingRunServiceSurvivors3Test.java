package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.InvoiceAmounts;
import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.InvoicingRules;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BillingCensusAccess.BillingMember;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * E11-T06 PIT session-10 survivors of {@link BillingRunService} (S12 R-12-08/09; T-12-09): an invoice freezes the account
 * holder's tax id when the payment method has one, else the member's. Fixture copied from {@link BillingRunServiceSurvivorsTest};
 * fictional members only.
 */
class BillingRunServiceSurvivors3Test {
    static final String CLUB = "club-a";
    static final YearMonth PERIOD = YearMonth.of(2026, 9);
    static final LocalDate ISSUE = LocalDate.of(2026, 8, 25);
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");
    static final Instant SIGNED = Instant.parse("2025-01-10T09:00:00Z");

    @Test void T_12_09_anInvoiceFreezesTheAccountHoldersTaxIdElseTheMembers() {
        var held = member("member-held", method("11111111H"));
        var withoutHolderTaxId = member("member-own", method(null));
        var withoutMethod = member("member-none", null);

        assertThat(issue(held).memberSnapshot().taxId()).isEqualTo("11111111H");
        assertThat(issue(withoutHolderTaxId).memberSnapshot().taxId()).isEqualTo("00000000T");
        assertThat(issue(withoutMethod).memberSnapshot().taxId()).isEqualTo("00000000T");
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static Invoice issue(BillingMember member) {
        return BillingRunService.invoice("invoice-" + member.id(), context(), "2026", 1L, ISSUE, PERIOD, member, List.of(line(4500)),
                PaymentMethodType.SEPA_DD, InvoiceStatus.COLLECTING, InvoiceKind.PERIODIC, "run-1", "remittance-1", false, null, NOW, null);
    }

    static InvoicingService.Context context() {
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        var config = new ClubConfig(club, Map.of("billing.sepa.collectionDayOfMonth", 1), Set.of(), null, Map.of());
        var settings = new InvoicingRules.Settings("EUR", 1, InvoicingRules.CashInvoicing.MONTHLY, 6, true, false, false, false,
                Set.of(PaymentMethodType.SEPA_DD, PaymentMethodType.MANUAL));
        return new InvoicingService.Context(config, settings, ISSUE, Locale.forLanguageTag("ca"));
    }

    static BillingCensusAccess.PaymentMethod method(String holderTaxId) {
        return new BillingCensusAccess.PaymentMethod("SEPA_DD", true, "ES** **** 1234", "Laura Serra", holderTaxId, "MANDATE-1", null, false, null, SIGNED);
    }

    static BillingMember member(String id, BillingCensusAccess.PaymentMethod method) {
        return new BillingMember(id, 1, "Laura", "Serra", "Puig", "ACTIVE", "plan-monthly", PERIOD.atDay(1), null, null, method, "00000000T", "ca", NOW);
    }

    static InvoicingRules.Line line(long amount) {
        var total = new Money(amount, "EUR");
        return new InvoicingRules.Line(InvoiceLineOrigin.MONTHLY_FEE, PERIOD, "price-1", null, null, "Quota",
                new InvoiceAmounts.Amounts(total, BigDecimal.ZERO, new Money(0, "EUR"), total), null);
    }
}
