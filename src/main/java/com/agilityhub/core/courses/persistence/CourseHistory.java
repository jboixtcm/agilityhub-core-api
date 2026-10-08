package com.agilityhub.core.courses.persistence;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.convert.ValueConverter;
import org.springframework.data.mongodb.core.mapping.Field;
/** The previous ten course models; the behavior task bounds the history. */
public record CourseHistory(long version, int schemaVersion, @Field(write = Field.Write.ALWAYS)
        @ValueConverter(CourseJsonConverter.class) JsonNode normalizedJson) { }
