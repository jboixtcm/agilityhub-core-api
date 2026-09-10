package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.*;
import java.time.Instant;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("upfront_payments")
public record UpfrontPayment(@Id String id,String clubId,String memberId,String dogId,String concept,String signupConcept,
        Money amountDue,Money amountPaid,String status,String provider,String checkoutSessionId,Instant createdAt,Instant paidAt) implements TenantEntity { }
