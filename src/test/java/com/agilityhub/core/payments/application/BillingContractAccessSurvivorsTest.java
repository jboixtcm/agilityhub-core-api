package com.agilityhub.core.payments.application;

import com.agilityhub.core.payments.domain.InvoiceKind;
import com.agilityhub.core.payments.domain.InvoiceLineOrigin;
import com.agilityhub.core.payments.domain.InvoiceStatus;
import com.agilityhub.core.payments.domain.PaymentMethodType;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingRunRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.BillingSimulationRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.InvoiceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.PackBalanceRepository;
import com.agilityhub.core.payments.persistence.BillingDocuments.RemittanceRepository;
import com.agilityhub.core.payments.persistence.Invoice;
import com.agilityhub.core.payments.persistence.SignupCheckoutRepository;
import com.agilityhub.core.payments.persistence.SignupCheckoutSession;
import com.agilityhub.core.payments.persistence.UpfrontPayment;
import com.agilityhub.core.payments.persistence.UpfrontPaymentRepository;
import com.agilityhub.core.platform.application.CensusClubSettings;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.platform.application.Module;
import com.agilityhub.core.shared.application.BillingCensusAccess;
import com.agilityhub.core.shared.application.BookingOwnerAccess;
import com.agilityhub.core.shared.application.CurrentUser;
import com.agilityhub.core.shared.application.DogOwnerAccess;
import com.agilityhub.core.shared.application.MemberIdentityAccess;
import com.agilityhub.core.shared.application.SignupCapabilities;
import com.agilityhub.core.shared.application.TeamMemberAccess;
import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.application.lists.ListQuery;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BillingContractAccess} (S12 R-12-27, §6; T-12-20, T-12-21, T-13-24): every id of a bulk action
 * is checked, the impersonated member is the caller, an ADMIN reads any checkout session of the club, another member's upfront
 * payment or a non-holder's invoice is 404, and the list queries are parsed.
 */
class BillingContractAccessSurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-08-25T08:00:00Z");

    final InvoiceRepository invoices = mock(InvoiceRepository.class);
    final UpfrontPaymentRepository upfront = mock(UpfrontPaymentRepository.class);
    final SignupCheckoutRepository checkouts = mock(SignupCheckoutRepository.class);
    final ClubConfigService configs = mock(ClubConfigService.class);
    final BillingCensusAccess census = mock(BillingCensusAccess.class);
    final BillingContractAccess access = new BillingContractAccess(invoices, mock(BillingRunRepository.class), mock(BillingSimulationRepository.class),
            mock(RemittanceRepository.class), upfront, mock(PackBalanceRepository.class), checkouts, mock(MemberIdentityAccess.class), mock(DogOwnerAccess.class),
            configs, mock(CensusClubSettings.class), mock(SignupCapabilities.class), mock(TeamMemberAccess.class), census, mock(BookingOwnerAccess.class));

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void T_12_21_aBulkActionChecksEveryInvoiceId() {
        when(invoices.findById("invoice-1")).thenReturn(Optional.of(invoice("invoice-1", "member-a")));
        assertThatCode(() -> access.invoices(List.of("invoice-1"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.invoices(List.of("invoice-1", "invoice-unknown"))).hasMessage("NOT_FOUND");
    }

    @Test void T_13_24_underImpersonationTheCallerIsTheImpersonatedMember() {
        try (var user = CurrentUser.open(new CurrentUser("account-admin", "Admin", new CurrentUser.Impersonation("account-admin", "Admin", "member-impersonated"), null))) {
            assertThat(access.callerMember()).isEqualTo("member-impersonated");
        }
    }

    @Test void T_12_21_anAdminReadsAnotherMembersCheckoutSessionAndAMemberDoesNot() {
        // As CheckoutService/PaymentCheckouts insert it: PENDING (CheckoutStatus), with the rows it charges.
        when(checkouts.findById("session-1")).thenReturn(Optional.of(new SignupCheckoutSession("session-1", CLUB, "member-other", "PENDING", "payment",
                List.of("upfront-other"), NOW, null)));
        authenticate("member-admin", "ROLE_ADMIN");
        try (var user = CurrentUser.open(new CurrentUser("account-admin", "Admin", null, null))) {
            assertThatCode(() -> access.checkoutSession("session-1", null)).doesNotThrowAnyException();
        }
        authenticate("member-a", "ROLE_MEMBER");
        try (var user = CurrentUser.open(new CurrentUser("account-member", "Member", null, null))) {
            assertThatThrownBy(() -> access.checkoutSession("session-1", null)).hasMessage("NOT_FOUND");
        }
    }

    @Test void T_12_21_anotherMembersUpfrontPaymentIsNotFound() {
        when(upfront.findById("upfront-own")).thenReturn(Optional.of(upfrontPayment("upfront-own", "member-a")));
        when(upfront.findById("upfront-other")).thenReturn(Optional.of(upfrontPayment("upfront-other", "member-other")));
        assertThatCode(() -> access.checkoutReferences("member-a", null, List.of("upfront-own"))).doesNotThrowAnyException();
        assertThatThrownBy(() -> access.checkoutReferences("member-a", null, List.of("upfront-other"))).hasMessage("NOT_FOUND");
    }

    @Test void T_12_20_aFamilyMemberReadsTheHoldersInvoiceButNotAnotherMembers() {
        when(configs.get(CLUB)).thenReturn(new ClubConfig(null, Map.of(), Set.of(Module.FAMILY_GROUP), null, Map.of()));
        when(census.familyGroupOf("member-a")).thenReturn(Optional.of(new BillingCensusAccess.FamilyGroup("group-1", "member-holder",
                List.of("member-holder", "member-a"))));
        when(invoices.findById("invoice-holder")).thenReturn(Optional.of(invoice("invoice-holder", "member-holder")));
        when(invoices.findById("invoice-other")).thenReturn(Optional.of(invoice("invoice-other", "member-other")));
        try (var tenant = TenantContext.open(CLUB);
             var user = CurrentUser.open(new CurrentUser("account-admin", "Admin", new CurrentUser.Impersonation("account-admin", "Admin", "member-a"), null))) {
            assertThatCode(() -> access.ownInvoice("invoice-holder")).doesNotThrowAnyException();
            assertThatThrownBy(() -> access.ownInvoice("invoice-other")).hasMessage("NOT_FOUND");
        }
    }

    @Test void T_12_21_theListQueriesAreParsedWithTheUniversalDefaults() {
        assertThat(access.invoiceList(new LinkedMultiValueMap<>())).extracting(ListQuery::page, ListQuery::size).containsExactly(0, 50);
        assertThat(access.remittanceList(new LinkedMultiValueMap<>())).extracting(ListQuery::page, ListQuery::size).containsExactly(0, 50);
    }

    private static void authenticate(String memberId, String role) {
        var jwt = Jwt.withTokenValue("token").header("alg", "none").subject("account-" + memberId).claim("memberId", memberId).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role))));
    }

    /** A signup row as UpfrontPayments.create writes it: DUE (UpfrontStatus), no provider yet, under its submission. */
    static UpfrontPayment upfrontPayment(String id, String memberId) {
        return new UpfrontPayment(id, CLUB, memberId, "dog-1", "ENTRY_FEE", "ENTRY_FEE", new Money(3000, "EUR"), new Money(0, "EUR"), "DUE", null, null,
                NOW, null, null, "submission-1", null);
    }

    static Invoice invoice(String id, String memberId) {
        var total = new Money(4500, "EUR");
        return new Invoice(id, CLUB, "2026", 1, "2026-0001", "2026-08-25", "2026-09", memberId, new Invoice.MemberSnapshot(1, "Laura Serra", null),
                List.of(new Invoice.Line(1, InvoiceLineOrigin.MONTHLY_FEE, "price-1", null, "Quota", total, BigDecimal.ZERO, new Money(0, "EUR"), total)),
                total, new Money(0, "EUR"), total, new Invoice.PaymentMethodSnapshot(PaymentMethodType.MANUAL, null, "Laura Serra", null, null, null),
                InvoiceStatus.PENDING, InvoiceKind.PERIODIC, "run-1", null, false, null, null, null, null, null, null, new Money(0, "EUR"), null, 0L, NOW, null,
                NOW, null);
    }
}
