package com.agilityhub.core.clubs.catalogs.application;

import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PlanType;
import com.agilityhub.core.shared.application.IcuMessageSource;
import com.agilityhub.core.shared.domain.Money;
import java.util.*;
import org.springframework.stereotype.Component;

/** S05 R-05-19 presentation; amounts remain separately available as Money. */
@Component
public class PriceLineFormatter {
    private final IcuMessageSource messages;
    public PriceLineFormatter(IcuMessageSource messages) { this.messages = messages; }
    public String line(PlanType type, Money amount, Integer months, String fallback, Locale locale) {
        if (amount == null) { return fallback == null ? messages.format("plans.price.unavailable", Map.of(), locale) : fallback; }
        String price = amount.format(locale);
        return switch (type) {
            case MONTHLY -> messages.format("plans.price.monthly", Map.of("amount", price), locale);
            case SINGLE_CLASS -> messages.format("plans.price.singleClass", Map.of("amount", price), locale);
            case PACK -> messages.format("plans.price.pack", Map.of("amount", price, "months", months), locale);
        };
    }
}
