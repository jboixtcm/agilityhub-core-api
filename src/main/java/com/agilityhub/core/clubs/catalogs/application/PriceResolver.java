package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import com.agilityhub.core.clubs.catalogs.persistence.*;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class PriceResolver {
    private final PriceRepository prices;
    public PriceResolver(PriceRepository prices) { this.prices = prices; }
    /** Application boundary for the demo pack scenarios; catalog persistence types stay inside their context. */
    public Optional<String> packPriceId(String planId, LocalDate date) { return current(planId, PriceConcept.PACK, date).map(Price::id); }
    public Optional<Price> current(String planId, PriceConcept concept, LocalDate date) { return prices.current(planId, concept, date); }
}
