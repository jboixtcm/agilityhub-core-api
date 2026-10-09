package com.agilityhub.core.payments.api;

import com.agilityhub.core.payments.application.BillingContractAccess;
import com.agilityhub.core.payments.application.BillingTransactions;
import com.agilityhub.core.payments.application.RemittanceService;
import com.agilityhub.core.payments.domain.RemittanceStatus;
import com.agilityhub.core.payments.persistence.Remittance;
import com.agilityhub.core.shared.application.lists.ListEngine;
import com.agilityhub.core.shared.domain.ApiException;
import com.agilityhub.core.shared.domain.ErrorCode;
import com.agilityhub.core.shared.domain.Money;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.Test;
import org.springframework.util.LinkedMultiValueMap;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link RemittancesController} (S12 §6, R-12-15, T-12-11, T-12-21): another club's remittance is 404
 * before it is read, linked or submitted, and the stored answer of a submission (what a repeated Idempotency-Key replays) is
 * the published remittance with the creditor's IBAN masked.
 */
class RemittancesControllerSurvivorsTest {
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");
    static final String IBAN = "ES0000000000000000004955";

    final BillingContractAccess access = mock(BillingContractAccess.class);
    final ListEngine lists = mock(ListEngine.class);
    final RemittanceService remittances = mock(RemittanceService.class);
    final BillingTransactions transactions = mock(BillingTransactions.class);
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    final RemittancesController controller = new RemittancesController(access, lists, remittances, transactions, mapper);

    @Test void T_12_21_anotherClubsRemittanceIsNotFoundBeforeItIsReadLinkedOrSubmitted() {
        doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(access).remittance("remittance-b");

        assertCode(() -> controller.remittance("remittance-b"), ErrorCode.NOT_FOUND);
        assertCode(() -> controller.remittanceFile("remittance-b"), ErrorCode.NOT_FOUND);
        assertCode(() -> controller.submitRemittance("remittance-b", new BillingRequests.SubmissionRequest(LocalDate.parse("2026-10-02")), UUID.randomUUID()),
                ErrorCode.NOT_FOUND);
        verifyNoInteractions(remittances, transactions);
    }

    @Test void T_12_21_theRemittanceListRunsOnlyInsideTheCallersTenant() {
        doThrow(new ApiException(ErrorCode.NO_MEMBERSHIP)).when(access).tenant();

        assertCode(() -> controller.remittances(new LinkedMultiValueMap<>()), ErrorCode.NO_MEMBERSHIP);
        verifyNoInteractions(lists);
    }

    @Test void T_12_11_theStoredAnswerOfASubmissionIsThePublishedRemittanceWithoutTheIban() {
        var submittedAt = LocalDate.parse("2026-10-02");
        when(remittances.submit("remittance-1", submittedAt)).thenReturn(remittance("remittance-1"));
        var stored = new AtomicReference<byte[]>();
        when(transactions.keyed(eq(200), any(), any())).thenAnswer(call -> {
            Remittance result = call.<Supplier<Remittance>>getArgument(1).get();
            stored.set(call.<Function<Remittance, byte[]>>getArgument(2).apply(result));
            return result;
        });

        var answer = controller.submitRemittance("remittance-1", new BillingRequests.SubmissionRequest(submittedAt), UUID.randomUUID());

        assertThat(answer.id()).isEqualTo("remittance-1");
        assertThat(stored.get()).as("the replayed body of the Idempotency-Key").isNotNull();
        String json = new String(stored.get(), StandardCharsets.UTF_8);
        assertThat(json).contains("\"id\":\"remittance-1\"", "4955").doesNotContain(IBAN);
    }

    // --- fixture ------------------------------------------------------------------------------------------------------------

    static void assertCode(ThrowingCallable call, ErrorCode code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(code));
    }

    static Remittance remittance(String id) {
        return new Remittance(id, "club-a", "run-1", "2026-10", "example-club-2026-10-1", NOW, "2026-10-05",
                new Remittance.Creditor("Club Example", "ES00ZZZ00000000000", IBAN, null), List.of("collection-1"), 1, new Money(4500, "EUR"), null,
                "remittances/remittance-1.xml", NOW, null, RemittanceStatus.SUBMITTED, NOW, "account-1", 2L, NOW, null);
    }
}
