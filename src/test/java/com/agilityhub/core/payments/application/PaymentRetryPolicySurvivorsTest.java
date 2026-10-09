package com.agilityhub.core.payments.application;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.agilityhub.core.payments.persistence.PaymentOperation;
import com.agilityhub.core.payments.persistence.PaymentOperationRepository;
import com.agilityhub.core.platform.application.ClubConfig;
import com.agilityhub.core.platform.application.ClubConfigService;
import com.agilityhub.core.shared.application.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PaymentRetryPolicy} (S12 R-12-21): a failure under a held claim counts the attempt, runs the
 * terminal transition only when the attempts are exhausted, and only then logs the operator warning; a lost claim does neither.
 */
class PaymentRetryPolicySurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-09-01T08:00:00Z");

    final ClubConfigService configs = mock(ClubConfigService.class);
    final PaymentOperationRepository operations = mock(PaymentOperationRepository.class);
    final BillingTransactions tx = mock(BillingTransactions.class);
    final PaymentRetryPolicy policy = new PaymentRetryPolicy(configs, operations, tx, Clock.fixed(NOW, ZoneOffset.UTC));
    final PaymentOperation operation = new PaymentOperation("op-1", CLUB, "FORGET", "cus_1", "cus_1", null, "forget:cus_1", null, null, null, NOW, null);
    final RuntimeException failure = new IllegalStateException("provider down");
    final Runnable failing = () -> { throw failure; };
    final AtomicInteger exhausted = new AtomicInteger();
    final Logger logger = (Logger) LoggerFactory.getLogger(PaymentRetryPolicy.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach void setUp() {
        TenantContext.open(CLUB);
        doAnswer(call -> call.<Supplier<?>>getArgument(0).get()).when(tx).run(any());
        var club = new ClubConfig.ClubView(CLUB, "club-a", "Example Club", List.of("ca"), "ca", "Europe/Madrid", "EUR", null, null, "ACTIVE", null);
        when(configs.get(CLUB)).thenReturn(new ClubConfig(club, Map.of("billing.stripeMaxAttempts", 3), Set.of(), null, Map.of()));
        when(operations.claim("op-1", false)).thenReturn(new PaymentOperationRepository.Claim("token-1", false));
        appender.start(); logger.addAppender(appender);
    }

    @AfterEach void tearDown() {
        logger.detachAppender(appender); appender.stop();
        TenantContext.clear();
    }

    List<ILoggingEvent> warnings() { return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList(); }

    @Test void T_12_15_anExhaustingFailureUnderTheClaimRunsTheTerminalTransitionAndWarnsOnce() {
        when(operations.fence("op-1", "token-1")).thenReturn(true);
        when(operations.failed("op-1", NOW, 3)).thenReturn(true);

        assertThatThrownBy(() -> policy.execute(operation, failing, exhausted::incrementAndGet)).isSameAs(failure);

        // The held claim lets the attempt be counted (kills the negated fence check) and the exhausted transition run.
        verify(operations).failed("op-1", NOW, 3);
        assertThat(exhausted).hasValue(1);
        assertThat(warnings()).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).startsWith("Payment operation recovery exhausted: eventId=op-1"));
        verify(operations).release("op-1", "token-1");
    }

    @Test void T_12_15_aFailureWithAttemptsLeftNeitherRunsTheTerminalTransitionNorWarns() {
        when(operations.fence("op-1", "token-1")).thenReturn(true);
        when(operations.failed("op-1", NOW, 3)).thenReturn(false);

        assertThatThrownBy(() -> policy.execute(operation, failing, exhausted::incrementAndGet)).isSameAs(failure);

        verify(operations).failed("op-1", NOW, 3);
        // Kills the negated `if (terminal)` and the lambda answering true instead of `terminal`.
        assertThat(exhausted).hasValue(0);
        assertThat(warnings()).isEmpty();
    }

    @Test void T_12_15_aFailureAfterTheClaimWasLostCountsNothingAndDoesNotWarn() {
        when(operations.fence("op-1", "token-1")).thenReturn(false);

        assertThatThrownBy(() -> policy.execute(operation, failing, exhausted::incrementAndGet)).isSameAs(failure);

        verify(operations, never()).failed(any(), any(), anyInt());
        assertThat(exhausted).hasValue(0);
        // Kills the lambda answering true instead of false for a lost claim.
        assertThat(warnings()).isEmpty();
    }

    @Test void T_12_17_aRefundReconciliationStatusIsLoggedAsAWarningWithItsId() {
        policy.warnRefund("re_1", "failed");

        assertThat(warnings()).singleElement().satisfies(event ->
                assertThat(event.getFormattedMessage()).startsWith("Refund reconciliation status=failed: eventId=re_1"));
    }
}
