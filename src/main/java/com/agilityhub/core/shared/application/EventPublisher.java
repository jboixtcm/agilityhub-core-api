package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.domain.DomainEvent;

public interface EventPublisher {
    String publish(DomainEvent event);
}
