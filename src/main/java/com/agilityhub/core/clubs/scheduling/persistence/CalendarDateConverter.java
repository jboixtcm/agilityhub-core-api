package com.agilityhub.core.clubs.scheduling.persistence;

import java.time.LocalDate;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;

/** Business dates have no offset; converting through a JVM-local midnight would change their meaning. */
public final class CalendarDateConverter implements MongoValueConverter<LocalDate, String> {
    @Override public LocalDate read(String value, MongoConversionContext context) { return LocalDate.parse(value); }
    @Override public String write(LocalDate value, MongoConversionContext context) { return value.toString(); }
}
