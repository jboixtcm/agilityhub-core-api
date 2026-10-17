package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.clubs.catalogs.domain.OfferEntity;
import com.agilityhub.core.clubs.catalogs.domain.PriceRules.Period;
import com.agilityhub.core.clubs.catalogs.domain.OfferTerms.PriceConcept;
import com.agilityhub.core.shared.domain.Money;
import java.math.BigDecimal;
import java.time.*;
import org.springframework.data.annotation.Id;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.*;

@Document("prices")
public record Price(@Id String id, String clubId, String planId, PriceConcept concept, Money amount, BigDecimal taxPercent,
        @ValueConverter(PriceDateConverter.class) LocalDate validFrom, @ValueConverter(PriceDateConverter.class) LocalDate validTo,
        long version, Instant createdAt, Instant updatedAt, String createdByAccountId, String updatedByAccountId)
        implements OfferEntity, Period { }
