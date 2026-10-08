package com.agilityhub.core.courses.persistence;
import java.time.LocalDate;
import org.springframework.data.mongodb.core.convert.MongoConversionContext;
import org.springframework.data.mongodb.core.convert.MongoValueConverter;
/** A calendar date never passes through the host's time zone. */
public final class CourseDateConverter implements MongoValueConverter<LocalDate, String> {
    @Override public LocalDate read(String value, MongoConversionContext context) { return LocalDate.parse(value); }
    @Override public String write(LocalDate value, MongoConversionContext context) { return value.toString(); }
}
