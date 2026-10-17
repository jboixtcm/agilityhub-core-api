package com.agilityhub.core.clubs.catalogs.persistence;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;

/**
 * INC-68: a price's validity days are stored as `YYYY-MM-DD` strings on every write path. `@Field(targetType = STRING)` never
 * applied to a whole-document write (no LocalDate -> String converter), which stored the JVM zone's midnight as a BSON date;
 * such a value, until {@link PriceRepository#normaliseValidityDates()} converts it, is read back with the same zone.
 */
public final class PriceDateConverter implements MongoValueConverter<LocalDate, Object> {
    @Override public LocalDate read(Object value, MongoConversionContext context) { return day(value); }
    @Override public Object write(LocalDate value, MongoConversionContext context) { return value.toString(); }

    static LocalDate day(Object value) {
        return value instanceof Date date ? date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate() : LocalDate.parse(value.toString());
    }
}
