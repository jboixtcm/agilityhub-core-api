package com.agilityhub.core.shared.application;

import com.agilityhub.core.shared.api.ApiExceptionHandler;
import com.agilityhub.core.shared.api.IdempotencyFilter;
import com.agilityhub.core.shared.api.IdempotentReplayAspect;
import com.agilityhub.core.shared.api.KeyedRoutes;
import com.agilityhub.core.shared.persistence.IdempotencyRepository;
import com.agilityhub.core.shared.persistence.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods = false)
public class SharedConfiguration {
    @Bean @ConditionalOnMissingBean(TimeZoneProvider.class)
    TimeZoneProvider timeZoneProvider() { return new MapTimeZoneProvider(Map.of()); }

    @Bean ClubClock clubClock(Clock clock, TimeZoneProvider zones) { return new DefaultClubClock(clock, zones); }

    @Bean ApplicationRunner sharedIndexes(IdempotencyRepository idempotency, OutboxRepository outbox) {
        return arguments -> { idempotency.ensureIndexes(); outbox.ensureIndexes(); };
    }

    /**
     * CONVENCIONS_API §7 (E5-T27, ruling E46): the keyed routes come from the handlers' own `Idempotency-Key` declarations, read at
     * the first request (the filter is created before the handler mapping).
     */
    @Bean IdempotencyFilter idempotencyFilter(IdempotencyRepository records, MongoTransactionManager transactions,
                                             Clock clock, ApiExceptionHandler errors, ObjectMapper mapper,
                                             @org.springframework.beans.factory.annotation.Qualifier("requestMappingHandlerMapping")
                                             org.springframework.beans.factory.ObjectProvider<org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping> mappings) {
        return new IdempotencyFilter(records, new TransactionTemplate(transactions), clock, errors, mapper, new KeyedRoutes(mappings::getObject));
    }

    /** CONVENCIONS_API §7 (E62): the replay answers only once the handler's own authorization accepted the token. */
    @Bean IdempotentReplayAspect idempotentReplayAspect() { return new IdempotentReplayAspect(); }

    @Bean OutboxDispatcher outboxDispatcher(OutboxRepository records, Map<String, DomainEventHandler<?>> handlers,
                                            MongoTransactionManager transactions, ObjectMapper mapper,
                                            Clock clock, MeterRegistry metrics) {
        return new OutboxDispatcher(records, handlers, new TransactionTemplate(transactions), mapper, clock, metrics, 10);
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "shared.scheduling.enabled", matchIfMissing = true)
    static class SchedulingConfiguration { }
}
