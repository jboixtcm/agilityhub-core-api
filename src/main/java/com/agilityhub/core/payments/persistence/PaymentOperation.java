package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.payments.application.PaymentProvider;
import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** Recoverable provider command, committed before the external call. Contains references and amounts, never customer contact data. */
@Document("payment_operations")
public record PaymentOperation(@Id String id, String clubId, String kind, String targetId, String providerRef, Money amount,
        String key, String reason, String actorId, PaymentProvider.OffSessionRequest charge, Instant createdAt, String resultId) implements TenantEntity { }
