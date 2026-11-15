package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.BillingQueries;
import com.agilityhub.core.payments.application.PackBalanceService;
import com.agilityhub.core.payments.application.PackViews;
import com.agilityhub.core.payments.application.ReceiptPdf;
import com.agilityhub.core.payments.domain.PackBalanceState;
import com.agilityhub.core.payments.persistence.PackBalance;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link MyBillingController} (S12 §6, R-12-27; T-12-20, T-12-21, T-13-24): screen 12 counts its pages
 * as ceil(totalItems / size), another member's invoice is 404 before it is read or rendered, and screen 13 lists every pack of
 * the caller.
 */
class MyBillingControllerSurvivorsTest {
    final BillingContractAccess access = mock(BillingContractAccess.class);
    final BillingQueries queries = mock(BillingQueries.class);
    final ReceiptPdf receipts = mock(ReceiptPdf.class);
    final PackBalanceService packs = mock(PackBalanceService.class);
    final PackViews packViews = mock(PackViews.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final MyBillingController controller = controller();

    MyBillingController controller() {
        var created = new MyBillingController(access, queries, receipts, mock(ClubConfigService.class));
        ReflectionTestUtils.setField(created, "packs", packs);
        ReflectionTestUtils.setField(created, "packViews", packViews);
        ReflectionTestUtils.setField(created, "mapper", mapper);
        return created;
    }

    @Test void T_12_20_theInvoicePageCountsItsPagesAsTheCeilingOfTotalOverSize() {
        when(access.me()).thenReturn("member-1");
        when(queries.memberInvoices("member-1", 0, 20)).thenReturn(new BillingQueries.MemberInvoices(List.of(), 0, 20, 20L));
        when(queries.memberInvoices("member-1", 1, 20)).thenReturn(new BillingQueries.MemberInvoices(List.of(), 1, 20, 21L));
        when(queries.memberInvoices("member-1", 2, 20)).thenReturn(new BillingQueries.MemberInvoices(List.of(), 2, 20, 0L));

        assertThat(controller.myInvoices(0, 20).totalPages()).isEqualTo(1);
        assertThat(controller.myInvoices(1, 20).totalPages()).isEqualTo(2);
        assertThat(controller.myInvoices(2, 20).totalPages()).isZero();
    }

    @Test void T_12_21_theInvoicesAreReadOnlyInsideTheCallersTenant() {
        doThrow(new ApiException(ErrorCode.NO_MEMBERSHIP)).when(access).tenant();

        assertCode(() -> controller.myInvoices(0, 20), ErrorCode.NO_MEMBERSHIP);
        verifyNoInteractions(queries);
    }

    @Test void T_13_24_anotherMembersInvoiceIsNotFoundBeforeItIsReadOrRendered() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).ownInvoice("invoice-other");

        assertCode(() -> controller.myInvoice("invoice-other"), ErrorCode.NOT_FOUND);
        assertCode(() -> controller.myInvoiceDocument("invoice-other"), ErrorCode.NOT_FOUND);
        verifyNoInteractions(queries, receipts);
    }

    @Test void T_12_07_thePackScreenListsEveryPackOfTheCaller() {
        when(access.me()).thenReturn("member-1");
        var pack = new PackBalance("pack-1", "club-a", "member-1", "dog-1", "plan-pack", null, 10, 0, 10, "2026-06-12", "2026-11-11",
                PackBalanceState.ACTIVE, List.of(), null, null, null, Map.of(), 0L, Instant.parse("2026-06-12T10:00:00Z"), null);
        Map<String, Object> view = Map.of("id", "pack-1");
        var detail = new BillingContracts.PackBalanceDetail("pack-1", "member-1", "dog-1", "plan-pack", "Pack 10", null, 10, 0, 10,
                LocalDate.parse("2026-06-12"), LocalDate.parse("2026-11-11"), PackBalanceState.ACTIVE, List.of(), null, null);
        when(packs.list("member-1", null)).thenReturn(List.of(pack));
        when(packViews.view(pack)).thenReturn(view);
        when(mapper.convertValue(view, BillingContracts.PackBalanceDetail.class)).thenReturn(detail);

        assertThat(controller.myPackBalances()).containsExactly(detail);
    }

    /**
     * PAY-09 (owner decision of 13 Nov 2026, S12 R-12-22): `POST /me/card-setup` opens the caller's own setup through the guarded
     * {@code createOwnCardSetup} (only a card payer), never the admin link's unguarded {@code create}; its refusal reaches the caller.
     */
    @Test void PAY_09_theMembersOwnCardSetupGoesThroughTheCardPayerGuard() {
        var checkouts = mock(com.agilityhub.core.payments.application.PaymentCheckouts.class);
        ReflectionTestUtils.setField(controller, "checkouts", checkouts);
        when(access.me()).thenReturn("member-1");
        var request = new BillingRequests.CardSetupRequest("https://club-a.example.test/ok", "https://club-a.example.test/ko");
        when(checkouts.createOwnCardSetup(eq("member-1"), eq(request.successUrl()), eq(request.cancelUrl()), any()))
                .thenReturn(new com.agilityhub.core.payments.application.CheckoutService.Result("https://checkout.example.test/setup", "session-1"));

        assertThat(controller.myCardSetup(request, java.util.UUID.randomUUID()).checkoutUrl()).isEqualTo("https://checkout.example.test/setup");

        when(checkouts.createOwnCardSetup(eq("member-1"), any(), any(), any())).thenThrow(new ApiException(ErrorCode.INVALID_STATE));
        assertCode(() -> controller.myCardSetup(request, java.util.UUID.randomUUID()), ErrorCode.INVALID_STATE);
        verify(access, times(2)).mutableMember("member-1");
        verify(checkouts, never()).create(any(), any(), any(), anyBoolean(), any(), any(), any());
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
