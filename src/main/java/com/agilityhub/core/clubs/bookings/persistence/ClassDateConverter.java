package com.agilityhub.core.clubs.bookings.persistence;

import java.time.LocalDate;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;

/** `classDate` is a club-local business date stored as `YYYY-MM-DD`, so `classDate < today` compares as text (R-10-06). */
public final class ClassDateConverter implements MongoValueConverter<LocalDate, String> {
    @Override public LocalDate read(String value, MongoConversionContext context) { return LocalDate.parse(value); }
    @Override public String write(LocalDate value, MongoConversionContext context) { return value.toString(); }
}
