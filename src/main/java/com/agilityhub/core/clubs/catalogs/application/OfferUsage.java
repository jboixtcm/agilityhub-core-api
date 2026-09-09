package com.agilityhub.core.clubs.catalogs.application;

import java.time.LocalDate;
import java.util.Map;

/** Read boundary for census and billing; downstream reference writers must coordinate with the plan lock. */
public interface OfferUsage {
    Map<String, Long> plan(String id);
    PriceUsage price(String id);
    record PriceUsage(boolean referenced, LocalDate lastBilled, boolean periodKnown) { }
}
