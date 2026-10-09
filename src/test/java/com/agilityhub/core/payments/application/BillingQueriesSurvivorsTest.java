package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.BillingRunRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingSimulationRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.ApiException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BillingQueries} (S12 R-12-27; T-12-20, T-12-21): an unknown run or receipt is 404, and the
 * family holder reads its own receipts once.
 */
class BillingQueriesSurvivorsTest {
    static final String CLUB = "club-a";
    final ClubConfigService configs = mock(ClubConfigService.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingQueries queries = new BillingQueries(mock(BillingSimulationRepository.class), mock(BillingRunRepository.class), mock(RemittanceRepository.class),
            mock(InvoiceRepository.class), mock(BillingRunService.class), census, configs);

    @Test void T_12_21_anUnknownRunOrReceiptIsNotFound() {
        assertThatThrownBy(() -> queries.run("run-unknown")).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
        assertThatThrownBy(() -> queries.memberInvoice("member-a", "invoice-unknown")).isInstanceOf(ApiException.class).hasMessage("NOT_FOUND");
    }

    @Test void T_12_20_theFamilyHolderReadsItsOwnReceiptsOnceAndAMemberAlsoTheHolders() {
        when(configs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(Module.FAMILY_GROUP), null, Map.of()));
        var group = new BillingCensusAccess.FamilyGroup("group-1", "member-holder", List.of("member-holder", "member-a"));
        when(census.familyGroupOf("member-holder")).thenReturn(Optional.of(group));
        when(census.familyGroupOf("member-a")).thenReturn(Optional.of(group));
        try (var tenant = TenantContext.open(CLUB)) {
            assertThat(queries.readableMembers("member-holder")).containsExactly("member-holder");
            assertThat(queries.readableMembers("member-a")).containsExactly("member-a", "member-holder");
        }
    }
}
