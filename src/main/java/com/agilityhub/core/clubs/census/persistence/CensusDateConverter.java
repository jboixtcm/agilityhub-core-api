package com.agilityhub.core.clubs.census.persistence;

import java.time.*;
import java.util.Date;
import org.springframework.data.mongodb.core.convert.*;

/** Club-local calendar dates have no UTC offset; ISO strings preserve their date across hosts. */
public class CensusDateConverter implements MongoValueConverter<LocalDate, Object> {
    @Override public LocalDate read(Object value, MongoConversionContext context) {
        return value instanceof Date date ? date.toInstant().atZone(ZoneOffset.UTC).toLocalDate() : LocalDate.parse(value.toString());
    }
    @Override public Object write(LocalDate value, MongoConversionContext context) { return value.toString(); }
}
