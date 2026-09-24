package com.agilityhub.core.payments.persistence;

import com.agilityhub.core.shared.domain.TenantEntity;
import java.time.Instant;
import java.util.List;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

/** A provider checkout: the signup one (S04) or, with `bookingId`, the payment of an S08 PAY_TO_BOOK booking. */
@Document("checkout_sessions")
public record SignupCheckoutSession(@Id String id,String clubId,String memberId,String status,String mode,List<String> upfrontPaymentIds,Instant expiresAt,String bookingId) implements TenantEntity { }
