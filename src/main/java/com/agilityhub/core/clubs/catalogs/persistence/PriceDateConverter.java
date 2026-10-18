package com.agilityhub.core.clubs.catalogs.persistence;

import com.agilityhub.core.shared.domain.StoredDays;
import java.time.LocalDate;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;

/**
 * INC-68: a price's validity days are stored as `YYYY-MM-DD` strings on every write path. `@Field(targetType = STRING)` never
 * applied to a whole-document write (no LocalDate -> String converter), which stored the writer JVM's local midnight as a
 * BSON date. Such a value, until {@link PriceRepository#normaliseValidityDates()} converts it, is read with
 * {@link StoredDays#day} (round 2), the rule the Playoff migration reads it with too, whatever the reading JVM's zone.
 */
public final class PriceDateConverter implements MongoValueConverter<LocalDate, Object> {
    @Override public LocalDate read(Object value, MongoConversionContext context) { return day(value); }
    @Override public Object write(LocalDate value, MongoConversionContext context) { return value.toString(); }

    static LocalDate day(Object value) { return StoredDays.day(value); }
}
