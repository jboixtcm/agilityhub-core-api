package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link AccountingLists} (S12 R-12-26, R-12-14; T-12-19): the provider serves the `accounting` key,
 * prints a cell's raw value, and leaves out the receipts of rolled-back runs only when there are some.
 */
class AccountingListsSurvivorsTest {
    static final String CLUB = "club-a";
    final ClubConfigService configs = mock(ClubConfigService.class);
    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final AccountingLists lists = new AccountingLists(configs, invoices);

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    /** SEPA-09 (owner decision of 13-11-2026): the two new columns come after the original sixteen, which keep their order. */
    @Test void SEPA_09_theLastCollectionsStatusAndTheReturnDateAreAddedAfterTheOriginalColumns() {
        assertThat(AccountingLists.COLUMNS).containsExactly("displayNumber", "issueDate", "period", "memberNumber", "fullName", "holderTaxId",
                "concept", "base", "taxPercent", "tax", "total", "method", "status", "collectionDate", "remittance", "paymentReference",
                "lastCollectionStatus", "failedAt");
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("account-admin").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(configs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(Module.BILLING), null, Map.of()));
        when(invoices.rolledBackRunIds()).thenReturn(List.of());
        try (var tenant = TenantContext.open(CLUB)) {
            var dataset = lists.dataset("accounting");
            assertThat(dataset.projection()).containsEntry("lastCollectionStatus", new Document("$first", "$accountingCollection.status"))
                    .containsEntry("failedAt", "$failedAt").containsEntry("collectionDate", "$paidAt");
            assertThat(dataset.nullablePaths()).contains("lastCollectionStatus", "failedAt");
            assertThat(dataset.definition().defaultColumns()).endsWith("lastCollectionStatus", "failedAt");
        }
    }

    @Test void T_12_19_theProviderServesTheAccountingKey() {
        assertThat(lists.keys()).containsExactly("accounting");
    }

    @Test void T_12_19_cellsArePrintedAsStoredAndRolledBackRunsAreScopedOutOnlyWhenThereAreSome() {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("account-admin").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        when(configs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(Module.BILLING), null, Map.of()));
        try (var tenant = TenantContext.open(CLUB)) {
            when(invoices.rolledBackRunIds()).thenReturn(List.of("run-rolled-back"));
            var dataset = lists.dataset("accounting");
            assertThat(dataset.label().apply("memberNumber", 7)).isEqualTo("7");
            assertThat(dataset.scope().apply(null)).isEqualTo(new Document("runId", new Document("$nin", List.of("run-rolled-back"))));
            when(invoices.rolledBackRunIds()).thenReturn(List.of());
            assertThat(lists.dataset("accounting").scope().apply(null)).isNull();
        }
    }
}
