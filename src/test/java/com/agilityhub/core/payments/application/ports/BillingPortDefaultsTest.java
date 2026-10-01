package com.agilityhub.core.payments.application.ports;

import com.agilityhub.core.payments.domain.BillingRunStatus;
import com.agilityhub.core.payments.domain.CollectionProvider;
import com.agilityhub.core.payments.domain.CollectionStatus;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.BillingRun;
import com.agilityhub.core.payments.persistence.Collection;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.platform.application.BillingProviderSettings;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E8-T02 step 1: the null objects of the invoicing ports, each until the task named in its port replaces it — no fee, nobody
 * leaving, no pack opened (E8-T05), no SEPA writer (`422 SEPA_NOT_CONFIGURED`, E8-T03), no card provider
 * (`422 PAYMENT_PROVIDER_NOT_ENABLED`, E8-T04) — and the local/test remittance stub without a file.
 */
class BillingPortDefaultsTest {
    final BillingPortDefaults defaults = new BillingPortDefaults();

    @Test void E8_T02_theNullObjectsKnowNoFeeNoLeaveNoPackNoWriterAndNoCardProvider() {
        assertThat(defaults.inactivityFees().feeFor("member", YearMonth.of(2026, 9))).isEmpty();
        assertThat(defaults.inactivityFees().feesForMonth(YearMonth.of(2026, 9))).isEmpty();
        assertThat(defaults.leaveBilling().lastInvoicedMonth("member")).isEmpty();
        assertThatCode(() -> defaults.packOpening().open("member", "dog", "payment", LocalDate.of(2026, 6, 12))).doesNotThrowAnyException();
        assertThatThrownBy(() -> defaults.remittanceWriter().write(null, List.of(), LocalDate.of(2026, 9, 1)))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.SEPA_NOT_CONFIGURED));
        assertThatThrownBy(() -> defaults.cardCharging().chargeRun("run"))
                .isInstanceOfSatisfying(ApiException.class, failure -> assertThat(failure.code()).isEqualTo(ErrorCode.PAYMENT_PROVIDER_NOT_ENABLED));
    }

    @Test void R_12_12_theInMemoryWriterNamesTheMessageAfterTheClubAndThePeriodAndSnapshotsTheCreditorWithoutAFile() {
        var remittances = mock(RemittanceRepository.class); var configs = mock(ClubConfigService.class); var providers = mock(BillingProviderSettings.class);
        var config = mock(ClubConfig.class); var view = mock(ClubConfig.ClubView.class);
        when(configs.get("club")).thenReturn(config); when(config.club()).thenReturn(view);
        when(view.slug()).thenReturn("a-club-with-a-very-long-slug-name"); when(view.currency()).thenReturn("EUR");
        when(remittances.forPeriod("2026-09")).thenReturn(List.of(mock(Remittance.class)));
        var clock = java.time.Clock.fixed(Instant.parse("2026-08-25T08:00:00Z"), java.time.ZoneOffset.UTC);
        var writer = new InMemoryRemittanceWriter(remittances, configs, providers, clock);
        var run = new BillingRun("run", "club", "2026-09", BillingRunStatus.GENERATED, "simulation", List.of(), null, "2026-09-01", clock.instant(), null,
                List.of(), List.of(), 1, "admin", null, null, null, clock.instant());
        try (var tenant = TenantContext.open("club")) {
            when(providers.sepaCreditor()).thenReturn(Optional.empty());
            var empty = writer.write(run, List.of(), LocalDate.of(2026, 9, 1));
            assertThat(empty.messageId()).hasSizeLessThanOrEqualTo(35).startsWith("a-club-with-a-very-long-slug-name-2");
            assertThat(empty.creditor()).isNull();
            assertThat(empty.total()).isEqualTo(new Money(0, "EUR"));
            assertThat(empty.fileKey()).isNull(); assertThat(empty.xsdValidatedAt()).isNull();
            when(providers.sepaCreditor()).thenReturn(Optional.of(new BillingProviderSettings.SepaCreditor("Club", "ES00ZZZB00000000", "ES0000000000000000000000", null, "000")));
            var attempts = List.of(attempt("c1", 6000), attempt("c2", 9000));
            var written = writer.write(run, attempts, LocalDate.of(2026, 9, 1));
            assertThat(written.creditor().id()).isEqualTo("ES00ZZZB00000000");
            assertThat(written.total()).isEqualTo(new Money(15000, "EUR"));
            assertThat(written.collectionIds()).containsExactly("c1", "c2");
            assertThat(written.sequenceBreakdown().rcur()).isEqualTo(2);
            assertThat(written.requestedCollectionDate()).isEqualTo("2026-09-01");
        }
    }
    static Collection attempt(String id, long cents) {
        return new Collection(id, "club", "invoice-" + id, CollectionProvider.SEPA_XML, new Money(cents, "EUR"), CollectionStatus.CREATED, null, "remittance", 1, null, null,
                List.of(), Instant.parse("2026-08-25T08:00:00Z"), null);
    }
}
