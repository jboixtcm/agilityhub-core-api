package com.agilityhub.core.payments.domain;

import com.agilityhub.core.shared.domain.DomainEvent;
import java.time.Instant;
import java.util.Map;

@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
public record SignupPaymentEvent(String type,String clubId,String aggregateId,Instant occurredAt,Map<String,Object> payload,
        String actorAccountId,String impersonatedMemberId,Origin origin) implements DomainEvent {
    @Override public String aggregateType() { return "UpfrontPayment"; }
}
