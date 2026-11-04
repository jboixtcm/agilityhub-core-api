package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.Money;
import com.agilityhub.core.shared.domain.TenantEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** One adjustment per eligible member and billing run month. Dates are ISO club calendar dates. */
@Document("invoice_concepts")
public record InvoiceConcept(@Id String id, String clubId, String description, Money amount, BigDecimal taxPercent,
        String firstPeriod, String endsOn, boolean allMembers, Set<String> memberIds,
        Instant createdAt, String createdBy, Instant cancelledAt, String cancelledBy) implements TenantEntity {}
