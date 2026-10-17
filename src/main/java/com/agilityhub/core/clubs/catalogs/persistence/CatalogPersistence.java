package com.agilityhub.core.clubs.catalogs.persistence;

import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Startup setup of the catalog collections, like the other `*Persistence` runners. */
@Configuration(proxyBeanMethods = false)
public class CatalogPersistence {
    /** INC-68 (E11-T08): the idempotent conversion of the price validity days stored as BSON dates, at every start. */
    @Bean ApplicationRunner priceValidityDays(PriceRepository prices) {
        return arguments -> {
            long converted = prices.normaliseValidityDates();
            if (converted > 0) { LoggerFactory.getLogger(CatalogPersistence.class).info("Price validity days converted for {} prices", converted); }
        };
    }
}
