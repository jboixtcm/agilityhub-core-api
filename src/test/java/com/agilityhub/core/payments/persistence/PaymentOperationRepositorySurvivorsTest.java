package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.application.TenantContext;
import com.agilityhub.core.shared.domain.Money;
import com.mongodb.client.result.UpdateResult;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.UpdateDefinition;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * E11-T06 PIT survivors of {@link PaymentOperationRepository} (S12 R-12-20, T-12-17): a refund webhook finds its command by
 * the provider's result id, and settling or reversing a refund reports whether it changed the command.
 */
class PaymentOperationRepositorySurvivorsTest {
    static final String CLUB = "club-a";
    static final Instant NOW = Instant.parse("2026-10-01T08:00:00Z");

    final MongoTemplate mongo = mock(MongoTemplate.class);
    final PaymentOperationRepository operations = new PaymentOperationRepository(mongo, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach void openTenant() { TenantContext.clear(); TenantContext.open(CLUB); }
    @AfterEach void closeTenant() { TenantContext.clear(); }

    @Test void T_12_17_aRefundWebhookFindsItsCommandByResultId() {
        var operation = new PaymentOperation("operation-1", CLUB, "REFUND", "upfront-1", "pi_fake_1", new Money(3000, "EUR"), "key-1",
                "Cancelled in time", "account-1", null, NOW, "re_fake_1");
        when(mongo.findOne(any(Query.class), eq(PaymentOperation.class))).thenReturn(operation);

        assertThat(operations.forResult("re_fake_1")).containsSame(operation);
    }

    @Test void T_12_17_aRefundIsSettledOnlyOnce() {
        var refund = new UpfrontPayment.Refund(new Money(3000, "EUR"), "re_fake_1", NOW, "Cancelled in time", "account-1");
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(PaymentOperation.class)))
                .thenReturn(UpdateResult.acknowledged(1, 1L, null), UpdateResult.acknowledged(1, 0L, null));

        assertThat(operations.refundSettled("operation-1", refund)).isTrue();
        assertThat(operations.refundSettled("operation-1", refund)).isFalse();
    }

    @Test void T_12_17_aReversedRefundReportsWhetherItWasStillThere() {
        when(mongo.updateFirst(any(Query.class), any(UpdateDefinition.class), eq(PaymentOperation.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null), UpdateResult.acknowledged(1, 1L, null));

        assertThat(operations.reverseRefund("operation-1", "re_fake_1")).isFalse();
        assertThat(operations.reverseRefund("operation-1", "re_fake_1")).isTrue();
    }
}
