package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.BillingQueries;
import com.agilityhub.core.payments.application.BillingRunService;
import com.agilityhub.core.payments.application.BillingSimulationService;
import com.agilityhub.core.payments.application.BillingTransactions;
import com.agilityhub.core.payments.application.ports.CardChargingPort;
import com.agilityhub.core.shared.application.AccountingExportPort;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link BillingController} (S12 §6, T-12-19, T-12-21): every route runs its tenant, month, format
 * or run guard before it reads, simulates, generates, rolls back or exports anything.
 */
class BillingControllerSurvivorsTest {
    final BillingContractAccess access = mock(BillingContractAccess.class);
    final BillingQueries queries = mock(BillingQueries.class);
    final BillingSimulationService simulations = mock(BillingSimulationService.class);
    final BillingRunService runs = mock(BillingRunService.class);
    final BillingTransactions transactions = mock(BillingTransactions.class);
    final AccountingExportPort accounting = mock(AccountingExportPort.class);
    final BillingController controller = new BillingController(access, queries, simulations, runs, transactions, mock(CardChargingPort.class),
            mock(ObjectMapper.class), accounting);

    @Test void T_12_21_theMonthIsReadOnlyInsideTheCallersTenant() {
        doThrow(new ApiException(ErrorCode.NO_MEMBERSHIP)).when(access).tenant();

        assertCode(() -> controller.billingPeriod("2026-09"), ErrorCode.NO_MEMBERSHIP);
        verifyNoInteractions(queries);
    }

    @Test void T_12_21_aSimulationRunsOnlyInsideTheCallersTenant() {
        doThrow(new ApiException(ErrorCode.NO_MEMBERSHIP)).when(access).tenant();

        assertCode(() -> controller.simulateBilling(new BillingRequests.SimulationRequest("2026-09")), ErrorCode.NO_MEMBERSHIP);
        verifyNoInteractions(simulations);
    }

    @Test void E11_T06_aRunOfAMalformedMonthIsAValidationErrorBeforeAnythingIsGenerated() {
        doThrow(BillingContractAccess.invalid("period")).when(access).month("period", "2026-13");

        // Without the guard YearMonth.parse would fail with a DateTimeParseException instead of 400 VALIDATION_ERROR.
        assertCode(() -> controller.createBillingRun(new BillingRequests.BillingRunRequest("2026-13", "simulation-1", null), UUID.randomUUID()),
                ErrorCode.VALIDATION_ERROR);
        verifyNoInteractions(runs, transactions);
    }

    @Test void T_12_21_anotherClubsRunIsNotFoundBeforeItIsReadOrRolledBack() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).run("run-b");

        assertCode(() -> controller.billingRun("run-b"), ErrorCode.NOT_FOUND);
        assertCode(() -> controller.rollbackBillingRun("run-b", new BillingRequests.RollbackRequest("Wrong month", "RETROCEDIR"), UUID.randomUUID()),
                ErrorCode.NOT_FOUND);
        verifyNoInteractions(queries, runs, transactions);
    }

    @Test void T_12_19_theAccountingExportChecksTheTenantAndTheFormatBeforeExporting() {
        doThrow(new ApiException(ErrorCode.NO_MEMBERSHIP)).when(access).tenant();
        assertCode(() -> controller.exportAccounting("2026-09", "csv"), ErrorCode.NO_MEMBERSHIP);

        doNothing().when(access).tenant();
        doThrow(BillingContractAccess.invalid("format")).when(access).oneOf("format", "pdf", "csv", "xlsx");
        assertCode(() -> controller.exportAccounting("2026-09", "pdf"), ErrorCode.VALIDATION_ERROR);

        verifyNoInteractions(accounting);
    }

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }
}
