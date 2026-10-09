package com.agilityhub.core.payments.api;

import com.agilityhub.core.clubs.catalogs.application.BillingCatalogAccess;
import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.BillingTransactions;
import com.agilityhub.core.payments.application.PackBalanceService;
import com.agilityhub.core.payments.application.PackViews;
import com.agilityhub.core.payments.domain.PackBalanceState;
import com.agilityhub.core.payments.persistence.PackBalance;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PackBalancesController} (S12 §6, R-12-23/24; T-12-07, T-12-21): the list answers every pack
 * of the member, a pack opened by hand answers the opened pack, and an erased member, another member's dog or another club's
 * pack is refused before anything is opened or adjusted.
 */
class PackBalancesControllerSurvivorsTest {
    static final LocalDate OPENED = LocalDate.parse("2026-06-12");

    final BillingContractAccess access = mock(BillingContractAccess.class);
    final PackBalanceService service = mock(PackBalanceService.class);
    final PackViews views = mock(PackViews.class);
    final BillingCatalogAccess catalog = mock(BillingCatalogAccess.class);
    final BillingTransactions transactions = mock(BillingTransactions.class);
    final ObjectMapper mapper = mock(ObjectMapper.class);
    final PackBalancesController controller = controller();

    PackBalancesController controller() {
        var created = new PackBalancesController(access);
        ReflectionTestUtils.setField(created, "service", service);
        ReflectionTestUtils.setField(created, "views", views);
        ReflectionTestUtils.setField(created, "catalog", catalog);
        ReflectionTestUtils.setField(created, "transactions", transactions);
        ReflectionTestUtils.setField(created, "mapper", mapper);
        return created;
    }

    @Test void T_12_07_theListAnswersEveryPackOfTheMember() {
        var pack = pack();
        Map<String, Object> view = Map.of("id", "pack-1");
        var detail = detail();
        when(service.list("member-1", null)).thenReturn(List.of(pack));
        when(views.view(pack)).thenReturn(view);
        when(mapper.convertValue(view, BillingContracts.PackBalanceDetail.class)).thenReturn(detail);

        assertThat(controller.packBalances("member-1", null)).containsExactly(detail);
        verify(access).member("member-1");
    }

    @Test void T_12_07_aPackOpenedByHandAnswersTheOpenedPack() {
        givenThePlan();
        when(transactions.keyed(anyInt(), any(), any())).thenAnswer(call -> call.<Supplier<?>>getArgument(1).get());
        var pack = pack();
        Map<String, Object> view = Map.of("id", "pack-1");
        var detail = detail();
        when(service.open("member-1", "dog-1", "plan-pack", null, OPENED, 10, null, "Gift")).thenReturn(pack);
        when(views.view(pack)).thenReturn(view);
        when(mapper.convertValue(view, BillingContracts.PackBalanceDetail.class)).thenReturn(detail);

        assertThat(controller.openPackBalance(request("member-1", "dog-1"), UUID.randomUUID())).isSameAs(detail);
    }

    @Test void T_12_21_anErasedMemberOrAnotherMembersDogOpensNoPack() {
        givenThePlan();
        doThrow(new ApiException(ErrorCode.MEMBER_ERASED)).when(access).mutableMember("member-erased");
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).memberDog("member-1", "dog-other");

        assertCode(() -> controller.openPackBalance(request("member-erased", "dog-1"), UUID.randomUUID()), ErrorCode.MEMBER_ERASED);
        assertCode(() -> controller.openPackBalance(request("member-1", "dog-other"), UUID.randomUUID()), ErrorCode.NOT_FOUND);
        verifyNoInteractions(service, transactions);
    }

    @Test void T_12_21_anotherClubsPackIsNotFoundBeforeItIsAdjusted() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).pack("pack-b");

        assertCode(() -> controller.adjustPackBalance("pack-b", new BillingRequests.PackAdjustmentRequest(-1, "Correction", null), UUID.randomUUID()),
                ErrorCode.NOT_FOUND);
        verifyNoInteractions(service, transactions);
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    void givenThePlan() {
        when(catalog.plan("plan-pack")).thenReturn(Optional.of(new BillingCatalogAccess.BillingPlan("plan-pack", "P10", "PACK", null, 1, null, null)));
    }

    static BillingRequests.PackBalanceRequest request(String memberId, String dogId) {
        return new BillingRequests.PackBalanceRequest(memberId, dogId, "plan-pack", 10, OPENED, null, "Gift");
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    static PackBalance pack() {
        return new PackBalance("pack-1", "club-a", "member-1", "dog-1", "plan-pack", null, 10, 0, 10, "2026-06-12", "2026-11-11",
                PackBalanceState.ACTIVE, List.of(), null, null, null, Map.of(), 0L, Instant.parse("2026-06-12T10:00:00Z"), null);
    }

    static BillingContracts.PackBalanceDetail detail() {
        return new BillingContracts.PackBalanceDetail("pack-1", "member-1", "dog-1", "plan-pack", "Pack 10", null, 10, 0, 10, OPENED,
                LocalDate.parse("2026-11-11"), PackBalanceState.ACTIVE, List.of(), null, null);
    }
}
