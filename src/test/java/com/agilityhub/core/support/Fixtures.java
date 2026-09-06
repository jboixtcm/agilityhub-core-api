package com.agilityhub.core.support;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;

/** Reads fictional JSON fixtures from the test classpath using the application's mapper. */
public final class Fixtures {

    private final ObjectMapper objectMapper;

    public Fixtures(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public <T> T read(String path, Class<T> type) {
        return read(path, objectMapper.constructType(type));
    }

    public <T> List<T> readList(String path, Class<T> elementType) {
        return read(path, objectMapper.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    private <T> T read(String path, JavaType type) {
        try (var input = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readValue(input, type);
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot load JSON fixture: " + path, exception);
        }
    }
}
