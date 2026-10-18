package com.agilityhub.core.clubs.catalogs.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Startup setup of the catalog collections, like the other `*Persistence` runners. */
@Configuration(proxyBeanMethods = false)
public class CatalogPersistence {
    private static final Logger LOG = LoggerFactory.getLogger(CatalogPersistence.class);

    /**
     * INC-68 (E11-T08): the idempotent conversion of the price validity days stored as BSON dates, at every start. Round 2:
     * the twins it keeps (two prices of a plan and concept starting the same day) are reported at every start, by count and
     * id, until they are resolved by hand (`docs/DEPLOY.md`); billing refuses those plans meanwhile (`PRICE_OVERLAP`).
     */
    @Bean ApplicationRunner priceValidityDays(PriceRepository prices) {
        return arguments -> {
            long converted = prices.normaliseValidityDates();
            if (converted > 0) { LOG.info("Price validity days converted for {} prices", converted); }
            var twins = prices.legacyStartTwins();
            if (!twins.isEmpty()) { LOG.warn("PriceStartTwins count={} ids={} (see docs/DEPLOY.md, «Price start twins»)", twins.size(), twins); }
        };
    }
}
